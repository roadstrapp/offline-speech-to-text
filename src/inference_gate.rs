//! Admission happens before taking an engine mutex. Waiting interactive final
//! decodes take precedence over the next subtitle/file/preparation window, and are
//! admitted among themselves in arrival order, so a later one cannot overtake one
//! already waiting.
use std::sync::{Condvar, Mutex};
#[derive(Default)]
struct State {
    busy: bool,
    /// Ticket for the next interactive arrival.
    next_ticket: u64,
    /// Ticket of the next interactive admission; equal to `next_ticket` when none waits.
    serving: u64,
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
        let ticket = interactive.then(|| {
            state.next_ticket += 1;
            state.next_ticket - 1
        });
        let blocked = |state: &State| {
            state.busy
                || match ticket {
                    Some(ticket) => state.serving != ticket,
                    None => state.serving != state.next_ticket,
                }
        };
        while blocked(&state) {
            state = self.ready.wait(state).unwrap_or_else(|p| p.into_inner());
        }
        if ticket.is_some() {
            state.serving += 1;
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
        wait_for_tickets(&gate, 1);
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
    fn wait_for_tickets(gate: &Gate, issued: u64) {
        while gate.state.lock().unwrap().next_ticket < issued {
            std::thread::yield_now();
        }
    }
    #[test]
    fn interactive_waiters_are_admitted_in_arrival_order() {
        // A keyboard final decode already waiting goes before a caller tail that
        // arrives later, and both go before the next background window.
        let gate = Arc::new(Gate::default());
        let held = gate.acquire(false);
        let (tx, rx) = mpsc::channel();
        let spawn = |name: &'static str, interactive: bool| {
            let (g, t) = (gate.clone(), tx.clone());
            std::thread::spawn(move || {
                let _permit = g.acquire(interactive);
                t.send(name).unwrap();
            })
        };
        let keyboard = spawn("keyboard final", true);
        wait_for_tickets(&gate, 1);
        let caller = spawn("caller tail", true);
        wait_for_tickets(&gate, 2);
        let background = spawn("background window", false);
        drop(held);
        assert_eq!(rx.recv().unwrap(), "keyboard final");
        assert_eq!(rx.recv().unwrap(), "caller tail");
        assert_eq!(rx.recv().unwrap(), "background window");
        for thread in [keyboard, caller, background] {
            thread.join().unwrap();
        }
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
