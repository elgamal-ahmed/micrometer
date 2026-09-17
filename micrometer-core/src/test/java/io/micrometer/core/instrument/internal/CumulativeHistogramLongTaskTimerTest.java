/*
 * Copyright 2026 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.core.instrument.internal;

import io.micrometer.core.Issue;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CumulativeHistogramLongTaskTimerTest {

    private MockClock clock;

    private CumulativeHistogramLongTaskTimer timer;

    @BeforeEach
    void setUp() {
        clock = new MockClock();
        timer = timer(DistributionStatisticConfig.builder()
            .serviceLevelObjectives(nanos(10), nanos(40), nanos(60))
            .build()
            .merge(DistributionStatisticConfig.DEFAULT));
    }

    @Test
    @Issue("#2744")
    void countsDoNotGrowWhileTheSameTaskStaysActive() {
        timer.start();

        for (int i = 0; i < 5; i++) {
            clock.addSeconds(1);
            assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);
        }
    }

    @Test
    @Issue("#2744")
    void countsDoNotGrowWhenSnapshotsAreTakenBackToBack() {
        timer.start();
        clock.addSeconds(1);

        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);
    }

    @Test
    @Issue("#2744")
    void countsDoNotGrowAsATaskAgesOutOfBuckets() {
        timer.start();

        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        // older than the 10s bucket, still within the 40s and 60s buckets
        clock.addSeconds(20);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        // older than every bucket
        clock.addSeconds(60);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);
    }

    @Test
    void countsAccumulateOverCompletedTasks() {
        LongTaskTimer.Sample sample = timer.start();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        sample.stop();
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        timer.start();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(2, 2, 2);
    }

    @Test
    void countsAccumulateOverConcurrentTasks() {
        timer.start();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        timer.start();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(2, 2, 2);
    }

    @Test
    void aTaskStartingAndFinishingBetweenSnapshotsIsNotCounted() {
        timer.start();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);

        LongTaskTimer.Sample sample = timer.start();
        clock.addSeconds(1);
        sample.stop();

        assertThat(takeSnapshotCounts()).containsExactly(1, 1, 1);
    }

    @Test
    void countsNeverDecreaseFromOneBucketToTheNext() {
        timer = timer(DistributionStatisticConfig.builder()
            .serviceLevelObjectives(nanos(10), nanos(60))
            .build()
            .merge(DistributionStatisticConfig.DEFAULT));

        // a narrow bucket turns tasks over faster than a wide one, so accumulating the
        // buckets independently lets the narrow one overtake the wide one
        int[] taskStarts = { 15, 25, 55, 65, 95, 105, 135 };
        List<double[]> snapshots = new ArrayList<>();
        for (int second = 1, next = 0; second <= 140; second++) {
            if (next < taskStarts.length && taskStarts[next] == second) {
                timer.start();
                next++;
            }
            clock.addSeconds(1);
            if (second % 20 == 0) {
                snapshots.add(takeSnapshotCounts());
            }
        }

        // four of the seven tasks were first seen while younger than 10s, and the wide
        // bucket never trails the narrow one even though it loses tasks more slowly
        assertThat(snapshots).containsExactly(new double[] { 1, 1 }, new double[] { 1, 2 }, new double[] { 2, 3 },
                new double[] { 2, 3 }, new double[] { 3, 4 }, new double[] { 3, 4 }, new double[] { 4, 5 });
        assertCountsAreCumulative(snapshots);
    }

    @Test
    void countsAreCumulativeWithAPercentileHistogram() {
        timer = timer(DistributionStatisticConfig.builder()
            .percentilesHistogram(true)
            .minimumExpectedValue(nanos(1))
            .maximumExpectedValue(nanos(120))
            .build()
            .merge(DistributionStatisticConfig.DEFAULT));

        List<double[]> snapshots = new ArrayList<>();
        for (int second = 1; second <= 100; second++) {
            if (second % 10 == 0) {
                timer.start();
            }
            clock.addSeconds(1);
            if (second % 25 == 0) {
                snapshots.add(takeSnapshotCounts());
            }
        }

        assertThat(snapshots).hasSize(4);
        assertThat(snapshots.get(0)).hasSizeGreaterThan(1);
        assertCountsAreCumulative(snapshots);
        // every one of the ten tasks was first seen while younger than the widest bucket
        assertThat(lastCount(snapshots.get(3))).isEqualTo(10);
    }

    @Test
    void onlyTheHistogramCountsAreCumulative() {
        timer = timer(DistributionStatisticConfig.builder()
            .serviceLevelObjectives(nanos(10), nanos(40), nanos(60))
            .percentiles(0.5)
            .build()
            .merge(DistributionStatisticConfig.DEFAULT));
        LongTaskTimer.Sample sample = timer.start();
        clock.addSeconds(1);
        timer.takeSnapshot();
        clock.addSeconds(1);
        sample.stop();

        HistogramSnapshot snapshot = timer.takeSnapshot();

        assertThat(snapshot.count()).isZero();
        assertThat(snapshot.total()).isZero();
        assertThat(snapshot.max()).isZero();
        assertThat(snapshot.percentileValues()).isNotNull();
        assertThat(snapshot.histogramCounts()).extracting(CountAtBucket::count).containsExactly(1.0, 1.0, 1.0);
    }

    @Test
    void bucketBoundariesArePreserved() {
        timer.start();
        clock.addSeconds(1);
        timer.takeSnapshot();
        clock.addSeconds(1);

        assertThat(timer.takeSnapshot().histogramCounts()).containsExactly(new CountAtBucket(nanos(10), 1),
                new CountAtBucket(nanos(40), 1), new CountAtBucket(nanos(60), 1));
    }

    @Test
    void noBucketsIsSupported() {
        timer = timer(DistributionStatisticConfig.DEFAULT);
        timer.start();

        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).isEmpty();
        clock.addSeconds(1);
        assertThat(takeSnapshotCounts()).isEmpty();
    }

    private void assertCountsAreCumulative(List<double[]> snapshots) {
        double[] previous = new double[snapshots.get(0).length];
        for (double[] counts : snapshots) {
            assertThat(counts).isSorted();
            for (int i = 0; i < counts.length; i++) {
                assertThat(counts[i]).isGreaterThanOrEqualTo(previous[i]);
            }
            previous = counts;
        }
    }

    private CumulativeHistogramLongTaskTimer timer(DistributionStatisticConfig config) {
        Meter.Id id = new Meter.Id("my.timer", Tags.empty(), null, null, Meter.Type.LONG_TASK_TIMER);
        return new CumulativeHistogramLongTaskTimer(id, clock, TimeUnit.NANOSECONDS, config);
    }

    private double[] takeSnapshotCounts() {
        HistogramSnapshot snapshot = timer.takeSnapshot();
        return Arrays.stream(snapshot.histogramCounts()).mapToDouble(CountAtBucket::count).toArray();
    }

    private static double lastCount(double[] counts) {
        return counts[counts.length - 1];
    }

    private static double nanos(int seconds) {
        return Duration.ofSeconds(seconds).toNanos();
    }

}
