// Appended to egg's tests/math.rs: runs one term under egg's own language, analysis and rules()
// with the runner's default limits (30 iterations, 10000 nodes; "math-75k" raises the nodes to
// 75000 as math_simplify_root does) and the time limit raised to ten minutes. With goals, as
// egg's test_fn! does, a hook stops the run once every goal matches at the start's class.

use std::time::Duration;

fn run_one(ruleset: &str, term: &str, goals: &[String]) -> String {
    let start: RecExpr<Math> = term.parse().unwrap();
    let rules = rules();
    let samples: Samples = Rc::new(RefCell::new(Vec::new()));
    let seen = samples.clone();
    let mut runner: Runner<Math, ConstantFold, ()> = Runner::default()
        .with_time_limit(Duration::from_secs(600))
        .with_hook(move |r| {
            seen.borrow_mut()
                .push((r.egraph.total_number_of_nodes(), r.egraph.number_of_classes()));
            Ok(())
        });
    runner = match ruleset {
        "math" => runner,
        "math-75k" => runner.with_node_limit(75_000),
        other => panic!("unknown ruleset {}", other),
    };
    runner = runner.with_expr(&start);
    if !goals.is_empty() {
        let id = runner.egraph.find(*runner.roots.last().unwrap());
        let patterns: Vec<Pattern<Math>> = goals.iter().map(|g| g.parse().unwrap()).collect();
        runner = runner.with_hook(move |r| {
            if patterns.iter().all(|g| g.search_eclass(&r.egraph, id).is_some()) {
                Err("Proved all goals".into())
            } else {
                Ok(())
            }
        });
    }
    let runner = runner.run(&rules);
    format_result(&runner, &samples)
}
