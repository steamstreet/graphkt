# GraphKt 3.0 Performance Baseline

This baseline measures the common GraphQL runtime on the JVM. The measurements do not control release acceptance.

## Run the baseline

Run this command from the repository root:

```shell
./gradlew :server:performanceBaseline
```

Run the task on a quiet host. When you compare two revisions, use the same host and JDK.

Do not compare measurements from different machines. Processor load, JVM compilation, and memory pressure can change the results.

## Method

The task uses 10 warm-up batches and 20 measurement batches. Each result reports median time, p95 time, and throughput.

The small document contains one operation, one variable, and three selected fields. The medium document contains 100 aliased node fields. The execution workloads use a small generated-style resolver tree. The batching workload loads 100 values with 50 unique keys. The encoding workloads serialize the small and medium execution responses.

The task has no external benchmark dependency. It gives a coarse regression signal, not a substitute for JMH or a production load test.

## Initial JVM result

Date: 2026-08-16

Environment: macOS, AArch64, JDK 17.0.10

| Workload | Median | p95 | Throughput |
|---|---:|---:|---:|
| Parse small document | 1,405 ns/op | 1,899 ns/op | 711,743 ops/s |
| Parse 100-field document | 49,207 ns/op | 51,261 ns/op | 20,322 ops/s |
| Validate small document | 3,391 ns/op | 4,385 ns/op | 294,898 ops/s |
| Validate 100-field document | 198,297 ns/op | 209,830 ns/op | 5,042 ops/s |
| Execute small request | 27,633 ns/op | 31,986 ns/op | 36,188 ops/s |
| Execute 100-field request | 501,314 ns/op | 558,404 ns/op | 1,994 ops/s |
| Batch 100 loads with 50 keys | 62,432 ns/op | 67,910 ns/op | 16,017 ops/s |
| Encode small response | 249 ns/op | 264 ns/op | 4,016,064 ops/s |
| Encode 100-field response | 11,267 ns/op | 12,099 ns/op | 88,754 ops/s |

Three consecutive runs differed by less than 7% for parser medians, 12% for validator medians, 13% for execution medians, and 2% for the batching median. Encoding medians differed by less than 16%. These results show the expected noise range for this host and task.

## Interpretation

Use repeated results to find large regressions. Investigate a repeatable median increase of 20% or more on the same host.

Record the environment and both revision identifiers with each comparison. Keep raw task output with the comparison record.
