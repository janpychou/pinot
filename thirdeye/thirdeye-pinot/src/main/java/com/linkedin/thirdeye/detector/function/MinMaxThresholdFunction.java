package com.linkedin.thirdeye.detector.function;

import com.google.common.base.Joiner;
import com.linkedin.thirdeye.api.DimensionKey;
import com.linkedin.thirdeye.api.MetricTimeSeries;
import com.linkedin.thirdeye.detector.db.entity.AnomalyResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See params for property configuration.
 * <p/>
 * min - lower threshold limit for average (inclusive). Will trigger alert if datapoint < min
 * (strictly less than)
 * <p/>
 * max - upper threshold limit for average (inclusive). Will trigger alert if datapoint > max
 * (strictly greater than)
 */
public class MinMaxThresholdFunction extends BaseAnomalyFunction {
  public static final String DEFAULT_MESSAGE_TEMPLATE = "min=%s, max=%s, value %s, change %s";
  public static final String MIN_VAL = "min";
  public static final String MAX_VAL = "max";

  public static String[] getPropertyKeys() {
    return new String [] {MIN_VAL, MAX_VAL};
  }

  @Override
  public List<AnomalyResult> analyze(DimensionKey dimensionKey,
      MetricTimeSeries timeSeries, DateTime windowStart, DateTime windowEnd,
      List<AnomalyResult> knownAnomalies) throws Exception {
    List<AnomalyResult> anomalyResults = new ArrayList<>();
    // Parse function properties
    Properties props = getProperties();

    // Metric
    String metric = getSpec().getMetric();

    // Get min / max props
    Double min = null;
    if (props.containsKey(MIN_VAL)) {
      min = Double.valueOf(props.getProperty(MIN_VAL));
    }

    Double max = null;
    if (props.containsKey(MAX_VAL)) {
      max = Double.valueOf(props.getProperty(MAX_VAL));
    }

    // Compute the weight of this time series (average across whole)
    double totalSum = 0;
    for (Long time : timeSeries.getTimeWindowSet()) {
      totalSum += timeSeries.get(time, metric).doubleValue();
    }
    // Compute the bucket size, so we can iterate in those steps
    long bucketMillis =
        TimeUnit.MILLISECONDS.convert(getSpec().getBucketSize(), getSpec().getBucketUnit());

    long numBuckets = (windowEnd.getMillis() - windowStart.getMillis()) / bucketMillis;

    // weight of this time series
    double averageValue = totalSum / numBuckets;

    for (Long timeBucket : timeSeries.getTimeWindowSet()) {
      Double value = timeSeries.get(timeBucket, metric).doubleValue();
      double deviationFromThreshold = getDeviationFromThreshold(value, min, max);

      if (deviationFromThreshold != 0) {
        AnomalyResult anomalyResult = new AnomalyResult();
        anomalyResult.setCollection(getSpec().getCollection());
        anomalyResult.setMetric(metric);
        anomalyResult.setDimensions(CSV.join(dimensionKey.getDimensionValues()));
        anomalyResult.setFunctionId(getSpec().getId());
        anomalyResult.setFunctionType(getSpec().getType());
        anomalyResult.setProperties(getSpec().getProperties());
        anomalyResult.setStartTimeUtc(timeBucket);
        anomalyResult.setEndTimeUtc(timeBucket + bucketMillis); // point-in-time
        anomalyResult.setScore(Math.abs(deviationFromThreshold)); // higher change, higher the score
        anomalyResult.setWeight(averageValue);
        String message =
            String.format(DEFAULT_MESSAGE_TEMPLATE, min, max, value, deviationFromThreshold);
        anomalyResult.setMessage(message);
        anomalyResult.setFilters(getSpec().getFilters());
        anomalyResults.add(anomalyResult);
      }
    }
    return mergeResults(anomalyResults, DEFAULT_MERGE_TIME_DELTA_MILLIS);
  }

  private double getDeviationFromThreshold(double currentValue, Double min, Double max) {
    if ((min != null && currentValue < min)) {
      return calculateChange(currentValue, min);
    } else if (max != null && currentValue > max) {
      return calculateChange(currentValue, max);
    }
    return 0;
  }
}