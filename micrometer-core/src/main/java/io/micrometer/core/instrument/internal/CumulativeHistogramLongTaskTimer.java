/*
 * Copyright 2020 VMware, Inc.
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

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;

import java.util.concurrent.TimeUnit;

/**
 * Extends the default long task timer, making histogram counts cumulative over time.
 * <p>
 * The counts reported by the superclass only cover the tasks that are active when the
 * snapshot is taken, so a bucket loses a task as soon as that task finishes or ages past
 * the bucket. This timer carries the counts it published before forward and adds the
 * tasks that entered each bucket since, which is what the Prometheus, OTLP and OpenTSDB
 * registries need in order to report a histogram that does not decrease.
 * <p>
 * Only the histogram buckets are cumulative, and only approximately so. Tasks are counted
 * from the change in the active counts between two snapshots, so a task that starts and
 * finishes between two snapshots is never counted, and a bucket narrower than the
 * interval between snapshots reports far fewer tasks than passed through it, possibly
 * none at all. {@link HistogramSnapshot#count()}, {@link HistogramSnapshot#total()} and
 * {@link HistogramSnapshot#max()} still describe the currently active tasks, so they do
 * not agree with the buckets.
 *
 * @author Jon Schneider
 * @since 1.5.2
 */
public class CumulativeHistogramLongTaskTimer extends DefaultLongTaskTimer {

    private double[] lastActiveCounts = {};

    private double[] cumulativeCounts = {};

    public CumulativeHistogramLongTaskTimer(Id id, Clock clock, TimeUnit baseTimeUnit,
            DistributionStatisticConfig distributionStatisticConfig) {
        super(id, clock, baseTimeUnit, distributionStatisticConfig, true);
    }

    @Override
    public synchronized HistogramSnapshot takeSnapshot() {
        HistogramSnapshot snapshot = super.takeSnapshot();
        CountAtBucket[] activeCounts = snapshot.histogramCounts();
        if (lastActiveCounts.length != activeCounts.length) {
            lastActiveCounts = new double[activeCounts.length];
            cumulativeCounts = new double[activeCounts.length];
        }

        CountAtBucket[] countAtBuckets = new CountAtBucket[activeCounts.length];
        // How many tasks entered a bucket is taken to be the increase in its active
        // count, which ignores the tasks that left the bucket over the same interval and
        // so is a lower bound. A task that entered a bucket also entered every wider one,
        // so each bucket takes the largest increase seen at or below it.
        double entered = 0;
        for (int i = 0; i < activeCounts.length; i++) {
            double activeCount = activeCounts[i].count();
            entered = Math.max(entered, activeCount - lastActiveCounts[i]);
            lastActiveCounts[i] = activeCount;
            cumulativeCounts[i] += entered;
            countAtBuckets[i] = new CountAtBucket(activeCounts[i].bucket(), cumulativeCounts[i]);
        }

        return new HistogramSnapshot(snapshot.count(), snapshot.total(), snapshot.max(), snapshot.percentileValues(),
                countAtBuckets, snapshot::outputSummary);
    }

}
