// Appended to egg's tests/math.rs: runs one term under egg's own language, analysis and rules()
// with the runner's default limits (30 iterations, 10000 nodes; "math-75k" raises the nodes to
// 75000 as math_simplify_root does) and the time limit raised to ten minutes; `math:name,name`
// runs the named rules only, under the default limits. With goals, as
// egg's test_fn! does, a hook stops the run once every goal matches at the start's class.

use std::time::Duration;

fn run_one(full_ruleset: &str, term: &str, goals: &[String]) -> String {
    // "<rule set>@<limit>,<ban>": egg's backoff scheduler with that match limit and ban length.
    let (ruleset, scheduler) = match full_ruleset.split_once('@') {
        Some((base, params)) => {
            let (limit, ban) = params.split_once(',').unwrap();
            (base, Some((limit.parse::<usize>().unwrap(), ban.parse::<usize>().unwrap())))
        }
        None => (full_ruleset, None),
    };
    let start: RecExpr<Math> = term.parse().unwrap();
    let rules: Vec<Rewrite> = match ruleset.strip_prefix("math:") {
        // "math:a,b,c": the rules of that name, for finding which of them a divergence needs.
        Some(names) => {
            let names: Vec<&str> = names.split(',').collect();
            let found: Vec<Rewrite> =
                rules().into_iter().filter(|rule| names.contains(&rule.name.as_str())).collect();
            if found.len() != names.len() {
                panic!("unknown or repeated rule name in {}", ruleset);
            }
            found
        }
        None => rules(),
    };
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
        other if other.starts_with("math:") => runner,
        other => panic!("unknown ruleset {}", other),
    };
    if let Some((limit, ban)) = scheduler {
        runner = runner.with_scheduler(
            BackoffScheduler::default().with_initial_match_limit(limit).with_ban_length(ban),
        );
    }
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
