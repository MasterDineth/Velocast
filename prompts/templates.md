# Prompt templates

## Kick-off (any step)

```text
Follow AGENTS.md. We are starting step <Sxx> from docs/steps.md. First read AGENTS.md, docs/architecture.md, docs/product-requirements.md and docs/findings.md, then restate the goal, list the files you expect to touch and any questions. Do not write code until I answer. After that, work on branch step/<Sxx>-<name> and use the prompt in prompts/<Sxx>.md.
```

## Continue after a limit

```text
Continue step <Sxx>. Read PROGRESS.md and git log for this branch, check ./gradlew check, then carry on from the next unfinished item. Do not redo completed work. Update PROGRESS.md when you stop.
```

## Review the agent's work

```text
Review the diff on branch step/<Sxx>-<name> against the acceptance criteria in docs/steps.md. List: criteria met with evidence, criteria not met, risks, missing tests, and any change outside the step's scope. Do not change code; report only.
```

## Fix a failing CI run

```text
CI failed on branch step/<Sxx>-<name>. Here is the log: <paste>. Find the root cause, fix it with the smallest correct change, add a regression test if it was a logic error, and confirm ./gradlew check passes. Do not disable tests or lint rules.
```

## Report device-test results

```text
Here are my device test results for step <Sxx> (device, Android version, ROM, what I did, what happened, logs): <paste>. Update the capability and compatibility documentation, fix defects you can fix with confidence, and list what still needs a different device.
```

## When the agent is stuck

```text
Stop. Summarise what you tried, what you observed and your current hypothesis. Propose two alternative approaches with trade-offs and wait for my choice. Do not keep retrying the same approach.
```
