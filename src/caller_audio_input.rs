//! Drain caller PCM before finalizing; cancellation and input errors never imply EOF.
use std::io::{self, ErrorKind, Read};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};

#[derive(Debug)]
pub(crate) enum Readiness {
    Readable,
    /// No data within the no-data limit while no stop was requested.
    Idle,
    /// A stop drain found nothing more readable.
    Drained,
    /// Cancelled, or the session cap claimed the input; the reader ends nothing itself.
    Cancelled,
}
#[derive(Debug)]
pub(crate) enum InputEnd {
    /// The writer closed (EOF/hang-up).
    Closed,
    Drained,
    Idle,
    Cancelled,
}

pub(crate) fn drain<R: Read>(
    mut source: R,
    mut wait: impl FnMut() -> io::Result<Readiness>,
    mut consume: impl FnMut(&[u8]),
) -> io::Result<InputEnd> {
    let mut bytes = [0u8; 8192];
    loop {
        match wait()? {
            Readiness::Cancelled => return Ok(InputEnd::Cancelled),
            Readiness::Idle => return Ok(InputEnd::Idle),
            Readiness::Drained => return Ok(InputEnd::Drained),
            Readiness::Readable => {}
        }
        match source.read(&mut bytes) {
            Ok(0) => return Ok(InputEnd::Closed),
            Ok(n) => consume(&bytes[..n]),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
}

/// Why a caller's input ended. Exactly one reason is published per job.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum EndReason {
    /// The caller closed the writer.
    Closed = 1,
    /// The caller stopped and its drain finished.
    Stopped = 2,
    /// No data arrived within the no-data limit.
    Idle = 3,
    /// The session cap.
    Cap = 4,
}

/// Publishes `reason` unless another reason already won. Only the winner may finalize
/// the input, so the reason and the finalization never disagree.
pub(crate) fn publish_end(slot: &AtomicU8, reason: EndReason) -> bool {
    slot.compare_exchange(0, reason as u8, Ordering::SeqCst, Ordering::SeqCst).is_ok()
}

/// Whether the published reason says the caller ended the input itself (closed the
/// writer or stopped), so someone is waiting on the result.
pub(crate) fn ended_by_caller(slot: &AtomicU8) -> bool {
    let reason = slot.load(Ordering::SeqCst);
    reason == EndReason::Closed as u8 || reason == EndReason::Stopped as u8
}

/// `stop_after_ms` is the one claim on how open input ends. `u64::MAX` means still open;
/// Stop, the no-data limit and the session cap each try to move it off that value, and
/// only the first succeeds. A real stop deadline is at least the drain budget, so it is
/// never one of the claim values below.
///
/// The no-data limit claimed the input; a later Stop or cap is a no-op.
pub(crate) const IDLE_CLAIMED: u64 = 0;
/// The session cap claimed the input; a later Stop is a no-op and the reader exits.
pub(crate) const CAP_CLAIMED: u64 = 1;

fn claim(stop_after_ms: &AtomicU64, value: u64) -> bool {
    stop_after_ms
        .compare_exchange(u64::MAX, value, Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
}

/// Returns whether this Stop won the claim; if so its bounded drain owns the end.
pub(crate) fn request_stop(stop_after_ms: &AtomicU64, deadline_ms: u64) -> bool {
    claim(stop_after_ms, deadline_ms)
}

/// The session cap may end only input that is still open. A Stop or no-data claim
/// already made keeps the end (and the drain) for itself.
pub(crate) fn claim_cap(stop_after_ms: &AtomicU64) -> bool {
    claim(stop_after_ms, CAP_CLAIMED)
}

/// Ends the input once: publish the reason, then mark the input closed, then release the
/// queue's tail, so whoever takes the tail sees the reason that decides its priority.
/// Returns false, doing nothing, if another reason already ended the input.
pub(crate) fn end_input(
    end_reason: &AtomicU8,
    input_closed: &AtomicBool,
    reason: EndReason,
    release_tail: impl FnOnce(),
) -> bool {
    if !publish_end(end_reason, reason) {
        return false;
    }
    input_closed.store(true, Ordering::SeqCst);
    release_tail();
    true
}

/// The session cap's tick once the cap is reached: it ends the input only if it claims
/// it while still open; otherwise the Stop or no-data claim keeps the end.
pub(crate) fn end_input_on_cap(
    stop_after_ms: &AtomicU64,
    end_reason: &AtomicU8,
    input_closed: &AtomicBool,
    release_tail: impl FnOnce(),
) -> bool {
    claim_cap(stop_after_ms) && end_input(end_reason, input_closed, EndReason::Cap, release_tail)
}

/// `wait_ready`, settling an idle expiry against a concurrent Stop: the expiry only
/// ends the input if it claims `stop_after_ms` first. If Stop got there between the
/// snapshot and the claim, wait again so Stop's bounded drain reads what is readable.
pub(crate) fn wait_ready_or_stop(
    stop_after_ms: &AtomicU64,
    mut state: impl FnMut() -> SourceState,
    mut poll: impl FnMut(i32) -> io::Result<bool>,
) -> io::Result<Readiness> {
    loop {
        match wait_ready(&mut state, &mut poll)? {
            Readiness::Idle if !claim(stop_after_ms, IDLE_CLAIMED) => {}
            ready => return Ok(ready),
        }
    }
}

/// Snapshot source lifecycle before every poll/read. Kept independent of JNI.
pub(crate) struct SourceState {
    pub cancelled: bool,
    pub finalized: bool,
    pub elapsed_ms: u64,
    pub stop_after_ms: u64,
    /// Time since the last byte arrived (or since the job started, before any did).
    pub idle_ms: u64,
    /// Whether any byte has arrived yet; selects which no-data limit applies.
    pub received_data: bool,
}

/// Once audio has started, an open descriptor that then delivers no bytes for this long
/// ends the input, so a caller that stalls or forgets to close it frees the shared slot
/// long before the cap. Silence is still PCM bytes, so a live recording never trips it.
pub(crate) const IDLE_INPUT_MS: u64 = 10_000;

/// Before the first byte the caller may still be recording into its own buffer and only
/// write when a chunk is sealed or the user sends, so wait this long instead. Closing the
/// writer still ends the input at once (EOF/hang-up), and the session cap still applies.
pub(crate) const FIRST_INPUT_MS: u64 = 60_000;

/// Absolute bound on one caller-audio session, from `startListening`. A live stream is
/// ended by its writer (EOF), by Stop or Cancel, or by the no-data limits above; this
/// cap only stops a caller that keeps writing forever from holding the one background
/// request slot and the CPU. Memory does not depend on it: pending audio is bounded by
/// the queue and consumed windows are discarded. One hour covers a long dictation or a
/// meeting, and its transcript (about 60 KB of text) is far below the 1 MB Binder limit
/// of the single `onResults`. At the cap the audio received so far is still transcribed.
pub(crate) const MAX_SESSION_MS: u64 = 60 * 60_000;

impl SourceState {
    pub(crate) fn no_data_limit_ms(&self) -> u64 {
        if self.received_data { IDLE_INPUT_MS } else { FIRST_INPUT_MS }
    }
}

pub(crate) fn wait_ready(
    mut state: impl FnMut() -> SourceState,
    mut poll: impl FnMut(i32) -> io::Result<bool>,
) -> io::Result<Readiness> {
    loop {
        let state = state();
        if state.cancelled || state.finalized || state.stop_after_ms == CAP_CLAIMED {
            return Ok(Readiness::Cancelled);
        }
        let stopping = state.stop_after_ms != u64::MAX;
        if stopping && state.elapsed_ms >= state.stop_after_ms {
            return Err(io::Error::from(ErrorKind::TimedOut));
        }
        if !stopping && state.idle_ms >= state.no_data_limit_ms() {
            // The limit is about a writer that sends nothing. Bytes already waiting mean
            // the reader was the one held up (the queue applies backpressure while
            // decoding is behind), so they are read instead of ending the input.
            match poll(0) {
                Ok(true) => return Ok(Readiness::Readable),
                Ok(false) => return Ok(Readiness::Idle),
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(e) => return Err(e),
            }
        }
        match poll(if stopping { 0 } else { 100 }) {
            Ok(true) => return Ok(Readiness::Readable),
            Ok(false) if stopping => return Ok(Readiness::Drained),
            Ok(false) => {}
            Err(e) if e.kind() == ErrorKind::Interrupted => {}
            Err(e) => return Err(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::net::UnixStream;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn cap_on_open_input_ends_it_once_and_later_ends_release_nothing() {
        let stop_after_ms = AtomicU64::new(u64::MAX);
        let end_reason = AtomicU8::new(0);
        let input_closed = AtomicBool::new(false);
        let releases = AtomicUsize::new(0);
        let release = || {
            assert_eq!(end_reason.load(Ordering::SeqCst), EndReason::Cap as u8);
            assert!(input_closed.load(Ordering::SeqCst));
            releases.fetch_add(1, Ordering::SeqCst);
        };
        assert!(end_input_on_cap(&stop_after_ms, &end_reason, &input_closed, release));
        assert!(!end_input_on_cap(&stop_after_ms, &end_reason, &input_closed, release));
        assert!(!end_input(&end_reason, &input_closed, EndReason::Closed, release));
        assert_eq!(releases.load(Ordering::SeqCst), 1);
        assert!(!ended_by_caller(&end_reason));
    }

    fn open(idle_ms: u64, received_data: bool) -> SourceState {
        SourceState {
            cancelled: false,
            finalized: false,
            elapsed_ms: idle_ms,
            stop_after_ms: u64::MAX,
            idle_ms,
            received_data,
        }
    }

    #[test]
    fn session_cap_fits_a_live_stream_and_keeps_the_stall_limits_short() {
        // Long dictation is not cut at one minute any more; a stalled writer still is
        // ended in seconds, and a writer that never starts within a minute.
        assert_eq!(MAX_SESSION_MS, 60 * 60_000);
        assert!(MAX_SESSION_MS >= 30 * 60_000);
        assert_eq!(IDLE_INPUT_MS, 10_000);
        assert_eq!(FIRST_INPUT_MS, 60_000);
        assert!(FIRST_INPUT_MS < MAX_SESSION_MS && IDLE_INPUT_MS < FIRST_INPUT_MS);
    }

    #[test]
    fn stalled_writer_ends_the_input_after_one_last_look() {
        for (idle_ms, received) in [(IDLE_INPUT_MS, true), (FIRST_INPUT_MS, false)] {
            let mut timeouts = Vec::new();
            let ready = wait_ready(|| open(idle_ms, received), |timeout| {
                timeouts.push(timeout);
                Ok(false)
            });
            assert!(matches!(ready.unwrap(), Readiness::Idle));
            // One non-blocking look, never a wait.
            assert_eq!(timeouts, [0]);
        }
    }

    #[test]
    fn bytes_waiting_past_the_idle_limit_are_read_not_treated_as_a_stall() {
        // The reader was blocked by queue backpressure for longer than the limit while
        // the writer kept writing: the pipe is readable, so the input stays open.
        let ready = wait_ready(|| open(IDLE_INPUT_MS * 3, true), |timeout| {
            assert_eq!(timeout, 0);
            Ok(true)
        });
        assert!(matches!(ready.unwrap(), Readiness::Readable));
        let stop_after_ms = AtomicU64::new(u64::MAX);
        let ready = wait_ready_or_stop(&stop_after_ms, || open(IDLE_INPUT_MS * 3, true), |_| Ok(true));
        assert!(matches!(ready.unwrap(), Readiness::Readable));
        // Nothing claimed the end: Stop, the cap or a later real stall still can.
        assert_eq!(stop_after_ms.load(Ordering::SeqCst), u64::MAX);
    }

    #[test]
    fn silence_is_data_so_a_long_pause_keeps_a_live_stream_open() {
        // Fake clock: a live writer delivers two minutes of zeros (PCM16, 16 kHz) at real
        // time. No limit looks at what the bytes contain, so the pause ends nothing.
        use std::cell::Cell;
        let pcm = vec![0u8; 120 * 32_000];
        let now = Cell::new(0u64);
        let last_data = Cell::new(0u64);
        let mut received = 0usize;
        let end = drain(
            pcm.as_slice(),
            || {
                wait_ready(
                    || SourceState { idle_ms: now.get() - last_data.get(), ..open(now.get(), true) },
                    |_| Ok(true),
                )
            },
            |bytes| {
                // One read holds this much audio; a real-time writer took that long.
                now.set(now.get() + bytes.len() as u64 / 32);
                last_data.set(now.get());
                received += bytes.len();
            },
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Closed));
        assert_eq!(received, pcm.len());
        assert!(now.get() >= 120_000 - 1);
    }

    #[test]
    fn closed_caller_stream_is_drained_across_multiple_reads() {
        let (mut writer, mut reader) = UnixStream::pair().unwrap();
        let pcm: Vec<u8> = (0..24_000).map(|n| (n % 251) as u8).collect();
        let feeder = {
            let pcm = pcm.clone();
            std::thread::spawn(move || writer.write_all(&pcm).unwrap())
        };
        let mut received = Vec::new();
        let reads = AtomicUsize::new(0);
        let end = drain(
            &mut reader,
            || {
                reads.fetch_add(1, Ordering::Relaxed);
                Ok(Readiness::Readable)
            },
            |bytes| received.extend_from_slice(bytes),
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Closed));
        feeder.join().unwrap();
        assert_eq!(received, pcm);
        assert!(reads.load(Ordering::Relaxed) >= 4);
    }

    #[test]
    fn open_writer_stop_drains_readable_bytes_then_finishes() {
        let (mut writer, mut reader) = UnixStream::pair().unwrap();
        let pcm = vec![42u8; 20_000];
        let feeder = {
            let pcm = pcm.clone();
            std::thread::spawn(move || {
                writer.write_all(&pcm).unwrap();
                writer
            })
        };
        let remaining = AtomicUsize::new(pcm.len());
        let mut received = Vec::new();
        let end = drain(
            &mut reader,
            || {
                Ok(if remaining.load(Ordering::Relaxed) == 0 {
                    Readiness::Drained
                } else {
                    Readiness::Readable
                })
            },
            |bytes| {
                remaining.fetch_sub(bytes.len(), Ordering::Relaxed);
                received.extend_from_slice(bytes);
            },
        )
        .unwrap();
        let writer = feeder.join().unwrap();
        assert!(matches!(end, InputEnd::Drained));
        assert_eq!(received, pcm);
        // Writer deliberately remains open: stop does not depend on future EOF.
        drop(writer);
    }

    #[test]
    fn cancellation_never_reads_waiting_audio() {
        let mut source = io::Cursor::new(vec![1u8; 10_000]);
        let mut bytes = 0;
        let end = drain(
            &mut source,
            || Ok(Readiness::Cancelled),
            |chunk| bytes += chunk.len(),
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Cancelled));
        assert_eq!(bytes, 0);
        assert_eq!(source.position(), 0);
    }

    #[test]
    fn poll_and_read_errors_are_not_successful_completion() {
        let mut source = io::Cursor::new(vec![1u8]);
        assert!(drain(
            &mut source,
            || Err(io::Error::from(ErrorKind::InvalidInput)),
            |_| {}
        )
        .is_err());
        struct Broken;
        impl Read for Broken {
            fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
                Err(io::Error::from(ErrorKind::BrokenPipe))
            }
        }
        assert!(drain(&mut Broken, || Ok(Readiness::Readable), |_| {}).is_err());
    }
}

#[cfg(all(test, target_os = "linux"))]
pub(crate) mod pipe_tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::time::Instant;
    #[repr(C)]
    struct PollFd {
        fd: i32,
        events: i16,
        revents: i16,
    }
    unsafe extern "C" {
        fn poll(fds: *mut PollFd, nfds: usize, timeout: i32) -> i32;
        fn pipe(fds: *mut i32) -> i32;
    }
    pub(crate) fn pair() -> (File, File) {
        let mut fds = [0; 2];
        assert_eq!(unsafe { pipe(fds.as_mut_ptr()) }, 0);
        unsafe { (File::from_raw_fd(fds[0]), File::from_raw_fd(fds[1])) }
    }
    pub(crate) fn kernel_poll(fd: i32, timeout: i32) -> io::Result<bool> {
        let mut p = PollFd {
            fd,
            events: 1,
            revents: 0,
        };
        let n = unsafe { poll(&mut p, 1, timeout) };
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        if p.revents & (8 | 32) != 0 {
            return Err(io::Error::from(ErrorKind::BrokenPipe));
        }
        Ok(n > 0)
    }
    pub(crate) fn stopped() -> SourceState {
        SourceState {
            cancelled: false,
            finalized: false,
            elapsed_ms: 0,
            stop_after_ms: 500,
            idle_ms: 0,
            received_data: false,
        }
    }
    #[test]
    fn actual_pipe_stop_drains_and_closes_before_terminal_work() {
        let (reader, mut writer) = pair();
        let pcm = vec![42; 4096];
        writer.write_all(&pcm).unwrap();
        let fd = reader.as_raw_fd();
        let mut received = Vec::new();
        let end = drain(
            reader,
            || {
                wait_ready(stopped, |t| {
                    assert_eq!(t, 0);
                    kernel_poll(fd, t)
                })
            },
            |bytes| received.extend_from_slice(bytes),
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Drained));
        assert_eq!(received, pcm);
        // drain owns and closes its descriptor before inference can begin.
        assert_eq!(
            writer.write(&[1]).unwrap_err().kind(),
            ErrorKind::BrokenPipe
        );
    }
    #[test]
    fn actual_pipe_hup_drains_to_eof() {
        let (reader, mut writer) = pair();
        writer.write_all(&[1; 4096]).unwrap();
        drop(writer);
        let fd = reader.as_raw_fd();
        let mut count = 0;
        let end = drain(
            reader,
            || wait_ready(stopped, |t| kernel_poll(fd, t)),
            |b| count += b.len(),
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Closed));
        assert_eq!(count, 4096);
    }
    #[test]
    fn actual_idle_open_pipe_finishes_on_stop() {
        let (reader, _writer) = pair();
        let fd = reader.as_raw_fd();
        assert!(matches!(
            wait_ready(stopped, |t| kernel_poll(fd, t)).unwrap(),
            Readiness::Drained
        ));
    }
    #[test]
    fn continuous_writer_is_cut_off_by_total_drain_deadline() {
        let (reader, mut writer) = pair();
        writer.write_all(&[42; 4096]).unwrap();
        let fd = reader.as_raw_fd();
        let producer = std::thread::spawn(move || while writer.write_all(&[42; 4096]).is_ok() {});
        let started = Instant::now();
        let mut count = 0;
        let result = drain(
            reader,
            || {
                wait_ready(
                    || SourceState {
                        cancelled: false,
                        finalized: false,
                        elapsed_ms: started.elapsed().as_millis() as u64,
                        stop_after_ms: 30,
                        idle_ms: 0,
                        received_data: true,
                    },
                    |t| kernel_poll(fd, t),
                )
            },
            |b| {
                count += b.len();
                std::thread::sleep(std::time::Duration::from_millis(1));
            },
        );
        // The producer may be momentarily quiescent, or continuously readable until timeout.
        assert!(
            matches!(result, Ok(InputEnd::Drained))
                || result.unwrap_err().kind() == ErrorKind::TimedOut
        );
        producer.join().unwrap();
        assert!(count > 0);
        assert!(started.elapsed().as_secs() < 2);
    }
    #[test]
    fn cancel_and_finalized_safety_cap_do_not_poll() {
        for finalized in [false, true] {
            let result = wait_ready(
                || SourceState {
                    cancelled: !finalized,
                    finalized,
                    elapsed_ms: 1,
                    stop_after_ms: 500,
                    idle_ms: 0,
                    received_data: false,
                },
                |_| panic!("cancelled capture must not poll"),
            );
            assert!(matches!(result.unwrap(), Readiness::Cancelled));
        }
        assert_eq!(
            wait_ready(
                || SourceState {
                    elapsed_ms: 500,
                    ..stopped()
                },
                |_| panic!("expired stop must not poll")
            )
            .unwrap_err()
            .kind(),
            ErrorKind::TimedOut
        );
    }
    #[test]
    fn silent_open_descriptor_finishes_after_idle_limit() {
        // A stall after audio started still ends the input after the short idle limit.
        let open = || SourceState {
            cancelled: false,
            finalized: false,
            elapsed_ms: 20_000,
            stop_after_ms: u64::MAX,
            idle_ms: IDLE_INPUT_MS,
            received_data: true,
        };
        assert!(matches!(
            wait_ready(open, |timeout| {
                assert_eq!(timeout, 0, "idle input must not wait");
                Ok(false)
            })
            .unwrap(),
            Readiness::Idle
        ));
        // Recent bytes keep the input open and polling.
        let mut polls = 0;
        let ready = wait_ready(
            || SourceState { idle_ms: IDLE_INPUT_MS - 1, ..open() },
            |_| {
                polls += 1;
                Ok(polls > 2)
            },
        );
        assert!(matches!(ready.unwrap(), Readiness::Readable));
        assert_eq!(polls, 3);
    }
    #[test]
    fn descriptor_that_never_writes_finishes_after_first_input_limit() {
        let waiting = |idle_ms| SourceState {
            cancelled: false,
            finalized: false,
            elapsed_ms: idle_ms,
            stop_after_ms: u64::MAX,
            idle_ms,
            received_data: false,
        };
        // Past the post-audio idle limit, but no byte yet: keep waiting.
        let ready = wait_ready(|| waiting(IDLE_INPUT_MS * 3), |_| Ok(true));
        assert!(matches!(ready.unwrap(), Readiness::Readable));
        assert!(matches!(
            wait_ready(|| waiting(FIRST_INPUT_MS), |timeout| {
                assert_eq!(timeout, 0, "expired wait must not wait");
                Ok(false)
            })
            .unwrap(),
            Readiness::Idle
        ));
    }
    #[test]
    fn late_first_write_after_long_silence_is_fully_received() {
        // Fake clock: the caller writes nothing for 30 s (buffering its own recording),
        // then sends the whole recording and closes. Every byte must reach the decoder.
        use std::cell::Cell;
        let pcm: Vec<u8> = (0..48_000).map(|n| (n % 253) as u8).collect();
        let now = Cell::new(0u64);
        let first_data = Cell::new(None::<u64>);
        let last_data = Cell::new(0u64);
        let mut received = Vec::new();
        let end = drain(
            io::Cursor::new(pcm.clone()),
            || {
                wait_ready(
                    || SourceState {
                        cancelled: false,
                        finalized: false,
                        elapsed_ms: now.get(),
                        stop_after_ms: u64::MAX,
                        idle_ms: now.get() - last_data.get(),
                        received_data: first_data.get().is_some(),
                    },
                    |timeout| {
                        if now.get() < 30_000 {
                            now.set(now.get() + timeout as u64);
                            Ok(false)
                        } else {
                            Ok(true)
                        }
                    },
                )
            },
            |bytes| {
                first_data.set(first_data.get().or(Some(now.get())));
                last_data.set(now.get());
                received.extend_from_slice(bytes);
            },
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Closed));
        assert_eq!(first_data.get(), Some(30_000));
        assert_eq!(received, pcm);
    }
    #[test]
    fn caller_stop_drain_ignores_idle_limit() {
        assert!(matches!(
            wait_ready(|| SourceState { idle_ms: FIRST_INPUT_MS * 2, ..stopped() }, |_| Ok(true)).unwrap(),
            Readiness::Readable
        ));
    }
    #[test]
    fn invalid_actual_descriptor_is_an_input_error() {
        assert_eq!(
            wait_ready(stopped, |t| kernel_poll(i32::MAX, t))
                .unwrap_err()
                .kind(),
            ErrorKind::BrokenPipe
        );
    }
    #[test]
    fn stop_landing_after_an_idle_snapshot_still_drains_readable_bytes() {
        // The no-data limit expires in a snapshot taken just before Stop arrives. Stop
        // wins the claim, so its drain runs and the bytes readable by then are kept.
        let (reader, mut writer) = pair();
        let pcm = vec![7u8; 4096];
        writer.write_all(&pcm).unwrap();
        let fd = reader.as_raw_fd();
        let stop_after_ms = AtomicU64::new(u64::MAX);
        let mut snapshots = 0;
        let mut received = Vec::new();
        let end = drain(
            reader,
            || {
                wait_ready_or_stop(
                    &stop_after_ms,
                    || {
                        let state = SourceState {
                            idle_ms: FIRST_INPUT_MS,
                            stop_after_ms: stop_after_ms.load(Ordering::SeqCst),
                            ..stopped()
                        };
                        snapshots += 1;
                        if snapshots == 1 {
                            assert!(request_stop(&stop_after_ms, 500));
                        }
                        state
                    },
                    |t| kernel_poll(fd, t),
                )
            },
            |bytes| received.extend_from_slice(bytes),
        )
        .unwrap();
        assert!(matches!(end, InputEnd::Drained));
        assert_eq!(received, pcm);
        assert_eq!(stop_after_ms.load(Ordering::SeqCst), 500);
        drop(writer);
    }
    #[test]
    fn idle_limit_claimed_before_stop_makes_stop_a_noop() {
        let stop_after_ms = AtomicU64::new(u64::MAX);
        let ready = wait_ready_or_stop(
            &stop_after_ms,
            || SourceState { idle_ms: FIRST_INPUT_MS, stop_after_ms: u64::MAX, ..stopped() },
            |timeout| {
                assert_eq!(timeout, 0, "expired wait must not wait");
                Ok(false)
            },
        );
        assert!(matches!(ready.unwrap(), Readiness::Idle));
        assert!(!request_stop(&stop_after_ms, 500));
        assert!(!claim_cap(&stop_after_ms));
        assert_eq!(stop_after_ms.load(Ordering::SeqCst), IDLE_CLAIMED);
    }
    #[test]
    fn cap_claim_on_open_input_ends_the_reader_and_blocks_later_claims() {
        let stop_after_ms = AtomicU64::new(u64::MAX);
        assert!(claim_cap(&stop_after_ms));
        assert!(!request_stop(&stop_after_ms, 500));
        assert!(!claim_cap(&stop_after_ms));
        let ready = wait_ready_or_stop(
            &stop_after_ms,
            || SourceState {
                idle_ms: FIRST_INPUT_MS,
                stop_after_ms: stop_after_ms.load(Ordering::SeqCst),
                ..stopped()
            },
            |_| panic!("a capped input must not poll"),
        );
        assert!(matches!(ready.unwrap(), Readiness::Cancelled));
        assert_eq!(stop_after_ms.load(Ordering::SeqCst), CAP_CLAIMED);
    }
    #[test]
    fn first_published_end_reason_decides_priority() {
        let slot = AtomicU8::new(0);
        assert!(!ended_by_caller(&slot));
        assert!(publish_end(&slot, EndReason::Closed));
        assert!(!publish_end(&slot, EndReason::Cap));
        assert!(ended_by_caller(&slot));

        let slot = AtomicU8::new(0);
        assert!(publish_end(&slot, EndReason::Cap));
        assert!(!publish_end(&slot, EndReason::Stopped));
        assert!(!ended_by_caller(&slot));

        let slot = AtomicU8::new(0);
        assert!(publish_end(&slot, EndReason::Idle));
        assert!(!ended_by_caller(&slot));
    }
}
