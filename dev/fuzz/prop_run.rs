// Appended to egg's tests/prop.rs: runs one term under egg's own language, analysis and rules,
// as `prove_something` does: the start is united with `true` first (lem_imply is sound only
// then) and rebuilt, the limits are the suite's (20 iterations, 5000 nodes) and the time limit is
// raised to ten minutes so that a timeout cannot hide a count. Rule sets: the whole file's, the
// two the file's tests use, and `prop:name,name` for the named rules of the file.

use std::time::Duration;

fn run_one(full_ruleset: &str, term: &str, _goals: &[String]) -> String {
    // "<rule set>@<limit>,<ban>": egg's backoff scheduler with that match limit and ban length.
    let (ruleset, scheduler) = match full_ruleset.split_once('@') {
        Some((base, params)) => {
            let (limit, ban) = params.split_once(',').unwrap();
            (base, Some((limit.parse::<usize>().unwrap(), ban.parse::<usize>().unwrap())))
        }
        None => (full_ruleset, None),
    };
    let start: RecExpr<Prop> = term.parse().unwrap();
    let all = || -> Vec<Rewrite> {
        vec![
            def_imply(), def_imply_flip(), double_neg(), double_neg_flip(), assoc_or(),
            dist_and_or(), dist_or_and(), comm_or(), comm_and(), lem(), or_true(), and_true(),
            contrapositive(), lem_imply(),
        ]
    };
    let rules: Vec<Rewrite> = match ruleset {
        "prop-all" => all(),
        // "prop:a,b,c": the rules of that name, for finding which of them a divergence needs.
        r if r.starts_with("prop:") => {
            let names: Vec<&str> = r["prop:".len()..].split(',').collect();
            let found: Vec<Rewrite> =
                all().into_iter().filter(|rule| names.contains(&rule.name.as_str())).collect();
            if found.len() != names.len() {
                panic!("unknown or repeated rule name in {}", r);
            }
            found
        }
        "prop-contrapositive" => vec![def_imply(), def_imply_flip(), double_neg_flip(), comm_or()],
        "prop-chain" => vec![
            def_imply(), def_imply_flip(), double_neg_flip(), comm_or(), comm_and(), lem_imply(),
        ],
        other => panic!("unknown ruleset {}", other),
    };
    let samples: Samples = Rc::new(RefCell::new(Vec::new()));
    let seen = samples.clone();
    let mut runner = Runner::default()
        .with_iter_limit(20)
        .with_node_limit(5_000)
        .with_time_limit(Duration::from_secs(600))
        .with_expr(&start)
        .with_hook(move |r| {
            seen.borrow_mut()
                .push((r.egraph.total_number_of_nodes(), r.egraph.number_of_classes()));
            Ok(())
        });
    if let Some((limit, ban)) = scheduler {
        runner = runner.with_scheduler(
            BackoffScheduler::default().with_initial_match_limit(limit).with_ban_length(ban),
        );
    }
    let true_id = runner.egraph.add(Prop::Bool(true));
    let root = runner.roots[0];
    runner.egraph.union(root, true_id);
    runner.egraph.rebuild();
    let runner = runner.run(&rules);
    format_result(&runner, &samples)
}
