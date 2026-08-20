# MIND Evaluation Harness

This directory contains the evaluation engine for the **Resonance Flow MIND team**.

The harness is designed to:

- Load a fixed set of persona scenarios.
- Execute a ranking model against those scenarios through a uniform interface.
- Compute performance metrics for the evaluated model.

## Project Context

- **Immutable Scenarios:** Scores a ranking model against `eval/personas/` — a fixed scenario set, never touched mid-comparison.
- **Foundation Gate:** Must be runnable by someone outside the MIND team.
- **Lifecycle:** Used for both the baseline (week 6) and the stretch-goal comparison (week 7, demo day week 9).
- **Metric choice:** Top-1 accuracy vs. MRR/nDCG — open decision, to be closed with mentor in week 1.

## The Model Interface (Contract)

To ensure that the evaluation harness can seamlessly test any ranking model, every model must implement the following contract. All models are evaluated strictly through this interface:

```python
class RankingModelInterface:
    def score(self, task: dict) -> float:
        """
        Evaluates a single task and returns a priority score.

        :param task: A dictionary representing a task. This input conforms to
                     the Inbox canonical schema, including fields such as
                     id, title, tier, urgency, effort_estimate, role_context, etc.

        :return: A float representing the calculated priority score.
                 A higher score indicates a higher priority for the given
                 time window.
        """
        pass
```
## Running the Evaluation

You can run the evaluation harness locally from the command line.

Pass the name of the model you wish to evaluate using the `--model` argument:

```bash
python -m mind.evaluation.run --model dummy
```
Replace `dummy` with the name of the model you want to evaluate.

## Metrics

Currently, the harness evaluates models using **Top-1 Accuracy**.

For each scenario in the evaluation set, the metric checks whether the task that received the highest score from the model matches the mentor-approved correct top task for that specific combination of:

- **Role**
- **Free-time window**
- **Scenario**

The final metric represents the proportion of scenarios where the model selected the correct top-priority task.
