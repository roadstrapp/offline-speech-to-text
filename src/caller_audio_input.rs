//! Drain caller PCM before finalizing; cancellation and input errors never imply EOF.
use std::io::{self, ErrorKind, Read};

#[derive(Debug)]
pub(crate) enum Readiness {
    Readable,
    Finished,
    Cancelled,
}
#[derive(Debug)]
pub(crate) enum InputEnd {
    Finished,
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
            Readiness::Finished => return Ok(InputEnd::Finished),
            Readiness::Readable => {}
        }
        match source.read(&mut bytes) {
            Ok(0) => return Ok(InputEnd::Finished),
            Ok(n) => consume(&bytes[..n]),
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) => return Err(e),
        }
    }
}

/// Snapshot source lifecycle before every poll/read. Kept independent of JNI.
pub(crate) struct SourceState {
    pub cancelled: bool,
    pub finalized: bool,
    pub elapsed_ms: u64,
    pub stop_after_ms: u64,
}

pub(crate) fn wait_ready(
    mut state: impl FnMut() -> SourceState,
    mut poll: impl FnMut(i32) -> io::Result<bool>,
) -> io::Result<Readiness> {
    loop {
        let state = state();
        if state.cancelled || state.finalized {
            return Ok(Readiness::Cancelled);
        }
        let stopping = state.stop_after_ms != u64::MAX;
        if stopping && state.elapsed_ms >= state.stop_after_ms {
            return Err(io::Error::from(ErrorKind::TimedOut));
        }
        match poll(if stopping { 0 } else { 100 }) {
            Ok(true) => return Ok(Readiness::Readable),
            Ok(false) if stopping => return Ok(Readiness::Finished),
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
    fn closed_caller_stream_is_drained_across_multiple_reads() {
        let (mut writer, mut reader) = UnixStream::pair().unwrap();
        let pcm: Vec<u8> = (0..24_000).map(|n| (n % 251) as u8).collect();
        writer.write_all(&pcm).unwrap();
        drop(writer);
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
        assert!(matches!(end, InputEnd::Finished));
        assert_eq!(received, pcm);
        assert!(reads.load(Ordering::Relaxed) >= 4);
    }

    #[test]
    fn open_writer_stop_drains_readable_bytes_then_finishes() {
        let (mut writer, mut reader) = UnixStream::pair().unwrap();
        let pcm = vec![42u8; 20_000];
        writer.write_all(&pcm).unwrap();
        let remaining = AtomicUsize::new(pcm.len());
        let mut received = Vec::new();
        let end = drain(
            &mut reader,
            || {
                Ok(if remaining.load(Ordering::Relaxed) == 0 {
                    Readiness::Finished
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
        assert!(matches!(end, InputEnd::Finished));
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
mod pipe_tests {
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
    fn pair() -> (File, File) {
        let mut fds = [0; 2];
        assert_eq!(unsafe { pipe(fds.as_mut_ptr()) }, 0);
        unsafe { (File::from_raw_fd(fds[0]), File::from_raw_fd(fds[1])) }
    }
    fn kernel_poll(fd: i32, timeout: i32) -> io::Result<bool> {
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
    fn stopped() -> SourceState {
        SourceState {
            cancelled: false,
            finalized: false,
            elapsed_ms: 0,
            stop_after_ms: 500,
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
        assert!(matches!(end, InputEnd::Finished));
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
        assert!(matches!(end, InputEnd::Finished));
        assert_eq!(count, 4096);
    }
    #[test]
    fn actual_idle_open_pipe_finishes_on_stop() {
        let (reader, _writer) = pair();
        let fd = reader.as_raw_fd();
        assert!(matches!(
            wait_ready(stopped, |t| kernel_poll(fd, t)).unwrap(),
            Readiness::Finished
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
            matches!(result, Ok(InputEnd::Finished))
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
    fn invalid_actual_descriptor_is_an_input_error() {
        assert_eq!(
            wait_ready(stopped, |t| kernel_poll(i32::MAX, t))
                .unwrap_err()
                .kind(),
            ErrorKind::BrokenPipe
        );
    }
}
