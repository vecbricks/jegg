// Appended to egg's tests/prop.rs or tests/math.rs by dev/fuzz.sh, after <lang>_run.rs, which
// defines `run_one`. Reads one case per line of $FUZZ_TERMS (ruleset, term, goals, tab
// separated), runs each through `run_one` and writes `<line number> <result>` to $FUZZ_OUT; a
// panic is the result PANIC. The result is `<STOP> <iterations> <n,e;...> final <n>,<e> memo <m>`:
// why the run stopped, egg's count of iterations, the nodes and classes at the start of each
// iteration (read by a hook, so jegg's runner reads the same point), after the run, and egg's
// memo size then: egg checks its node limit against the memo, which keeps stale entries, so a
// run can stop on a limit its nodes have not reached.

use std::cell::RefCell;
use std::rc::Rc;

type Samples = Rc<RefCell<Vec<(usize, usize)>>>;

fn format_result<L: Language, N: Analysis<L>>(runner: &Runner<L, N, ()>, samples: &Samples) -> String {
    let stop = match runner.stop_reason.as_ref() {
        Some(StopReason::Saturated) => "Saturated",
        Some(StopReason::IterationLimit(_)) => "IterationLimit",
        Some(StopReason::NodeLimit(_)) => "NodeLimit",
        Some(StopReason::TimeLimit(_)) => "TimeLimit",
        Some(StopReason::Other(_)) => "Other",
        None => "NoStop",
    };
    let seen: Vec<String> = samples
        .borrow()
        .iter()
        .map(|(n, e)| format!("{},{}", n, e))
        .collect();
    format!(
        "{} {} {} final {},{} memo {}",
        stop,
        runner.iterations.len(),
        if seen.is_empty() { "-".to_string() } else { seen.join(";") },
        runner.egraph.total_number_of_nodes(),
        runner.egraph.number_of_classes(),
        runner.egraph.total_size()
    )
}

#[test]
fn fuzz_run() {
    let terms_path = std::env::var("FUZZ_TERMS").expect("FUZZ_TERMS names the cases file");
    let out_path = std::env::var("FUZZ_OUT").expect("FUZZ_OUT names the results file");
    let text = std::fs::read_to_string(terms_path).unwrap();
    let mut out = String::new();
    for (k, line) in text.lines().enumerate() {
        let parts: Vec<String> = line.split('\t').map(|s| s.to_string()).collect();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_one(&parts[0], &parts[1], &parts[2..])
        }));
        match result {
            Ok(s) => out.push_str(&format!("{} {}\n", k, s)),
            Err(_) => out.push_str(&format!("{} PANIC\n", k)),
        }
    }
    std::fs::write(out_path, out).unwrap();
}
