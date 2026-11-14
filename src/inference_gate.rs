//! Admission happens before taking an engine mutex. Waiting interactive final
//! decodes take precedence over the next subtitle/file/preparation window.
use std::sync::{Condvar, Mutex};
#[derive(Default)]
struct State {
    busy: bool,
    interactive_waiters: usize,
}
#[derive(Default)]
pub(crate) struct Gate {
    state: Mutex<State>,
    ready: Condvar,
}
pub(crate) struct Permit<'a>(&'a Gate);
impl Gate {
    pub(crate) fn acquire(&self, interactive: bool) -> Permit<'_> {
        let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
        if interactive {
            state.interactive_waiters += 1;
        }
        while state.busy || (!interactive && state.interactive_waiters > 0) {
            state = self.ready.wait(state).unwrap_or_else(|p| p.into_inner());
        }
        if interactive {
            state.interactive_waiters -= 1;
        }
        state.busy = true;
        Permit(self)
    }
}
impl Drop for Permit<'_> {
    fn drop(&mut self) {
        self.0.state.lock().unwrap_or_else(|p| p.into_inner()).busy = false;
        self.0.ready.notify_all();
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{mpsc, Arc};
    #[test]
    fn interactive_waiter_precedes_next_background_window() {
        let gate = Arc::new(Gate::default());
        let held = gate.acquire(false);
        let (tx, rx) = mpsc::channel();
        let g = gate.clone();
        let t = tx.clone();
        let interactive = std::thread::spawn(move || {
            let _permit = g.acquire(true);
            t.send("interactive").unwrap();
        });
        while gate.state.lock().unwrap().interactive_waiters == 0 {
            std::thread::yield_now();
        }
        let g = gate.clone();
        let background = std::thread::spawn(move || {
            let _permit = g.acquire(false);
            tx.send("background").unwrap();
        });
        drop(held);
        assert_eq!(rx.recv().unwrap(), "interactive");
        assert_eq!(rx.recv().unwrap(), "background");
        interactive.join().unwrap();
        background.join().unwrap();
    }
    #[test]
    fn unwinding_releases_admission() {
        let gate = Gate::default();
        let _ = std::panic::catch_unwind(|| {
            let _permit = gate.acquire(false);
            panic!("injected");
        });
        let _permit = gate.acquire(true);
    }
}
