/*
 * Copyright 2006-2020 The MZmine Development Team
 *
 * This file is part of MZmine.
 *
 * MZmine is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with MZmine; if not,
 * write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package io.github.mzmine.modules.dataprocessing.filter_featurefilter;

import com.google.common.collect.Range;
import com.google.common.primitives.Booleans;
import io.github.mzmine.datamodel.FeatureStatus;
import io.github.mzmine.datamodel.MZmineProject;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.features.Feature;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.datamodel.features.ModularFeatureList;
import io.github.mzmine.datamodel.features.ModularFeatureListRow;
import io.github.mzmine.datamodel.features.SimpleFeatureListAppliedMethod;
import io.github.mzmine.modules.dataprocessing.filter_rowsfilter.RowsFilterParameters;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.taskcontrol.AbstractTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.MemoryMapStorage;
import io.github.mzmine.util.RangeUtils;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Filters out peaks from feature list.
 */
public class FeatureFilterTask extends AbstractTask {

  // Logger
  private static final Logger logger = Logger.getLogger(FeatureFilterTask.class.getName());

  // Feature lists
  private final MZmineProject project;
  private final FeatureList origPeakList;
  private ModularFeatureList filteredPeakList;

  // Processed rows counter
  private int processedRows;
  private int totalRows;

  // Parameters
  private final ParameterSet parameters;

  /**
   * Create the task.
   *
   * @param list         feature list to process.
   * @param parameterSet task parameters.
   */
  public FeatureFilterTask(final MZmineProject project, final FeatureList list,
      final ParameterSet parameterSet, @Nullable MemoryMapStorage storage) {
    super(storage);

    // Initialize
    this.project = project;
    parameters = parameterSet;
    origPeakList = list;
    filteredPeakList = null;
    processedRows = 0;
    totalRows = 0;
  }

  @Override
  public double getFinishedPercentage() {
    return totalRows == 0 ? 0.0 : (double) processedRows / (double) totalRows;
  }

  @Override
  public String getTaskDescription() {
    return "Filtering feature list";
  }

  @Override
  public void run() {

    if (isCanceled()) {
      return;
    }

    try {
      setStatus(TaskStatus.PROCESSING);
      logger.info("Filtering feature list");

      // Filter the feature list
      filteredPeakList = filterPeakList((ModularFeatureList) origPeakList);

      if (!isCanceled()) {

        // Add new peaklist to the project
        project.addFeatureList(filteredPeakList);

        // Remove the original peaklist if requested
        if (parameters.getParameter(FeatureFilterParameters.AUTO_REMOVE).getValue()) {
          project.removeFeatureList(origPeakList);
        }
        setStatus(TaskStatus.FINISHED);
        logger.info("Finished feature list filter");
      }
    } catch (Throwable t) {
      t.printStackTrace();
      setErrorMessage(t.getMessage());
      logger.log(Level.SEVERE, t.getMessage());
      setStatus(TaskStatus.ERROR);
    }

  }

  /**
   * Filter the feature list.
   *
   * @param peakList feature list to filter.
   * @return a new feature list with entries of the original feature list that pass the filtering.
   */
  private ModularFeatureList filterPeakList(final ModularFeatureList peakList) {

    // Make a copy of the peakList
    final ModularFeatureList newPeakList = peakList.createCopy(
        peakList.getName() + ' ' + parameters.getParameter(RowsFilterParameters.SUFFIX).getValue(),
        getMemoryMapStorage());

    // Get parameters - which filters are active
    final boolean filterByDuration =
        parameters.getParameter(FeatureFilterParameters.PEAK_DURATION).getValue();
    final boolean filterByArea =
        parameters.getParameter(FeatureFilterParameters.PEAK_AREA).getValue();
    final boolean filterByHeight =
        parameters.getParameter(FeatureFilterParameters.PEAK_HEIGHT).getValue();
    final boolean filterByDatapoints =
        parameters.getParameter(FeatureFilterParameters.PEAK_DATAPOINTS).getValue();
    final boolean filterByFWHM =
        parameters.getParameter(FeatureFilterParameters.PEAK_FWHM).getValue();
    final boolean filterByTailingFactor =
        parameters.getParameter(FeatureFilterParameters.PEAK_TAILINGFACTOR).getValue();
    final boolean filterByAsymmetryFactor =
        parameters.getParameter(FeatureFilterParameters.PEAK_ASYMMETRYFACTOR).getValue();
    final boolean filterByMS2 =
        parameters.getParameter(FeatureFilterParameters.MS2_Filter).getValue();

    final Range<Double> durationRange =
        parameters.getParameter(FeatureFilterParameters.PEAK_DURATION).getEmbeddedParameter()
            .getValue();
    final Range<Double> areaRange = parameters.getParameter(FeatureFilterParameters.PEAK_AREA)
        .getEmbeddedParameter().getValue();
    final Range<Double> heightRange = parameters
        .getParameter(FeatureFilterParameters.PEAK_HEIGHT).getEmbeddedParameter().getValue();
    final Range<Integer> datapointsRange =
        parameters.getParameter(FeatureFilterParameters.PEAK_DATAPOINTS)
            .getEmbeddedParameter().getValue();
    final Range<Float> fwhmRange = RangeUtils
        .toFloatRange(parameters.getParameter(FeatureFilterParameters.PEAK_FWHM)
            .getEmbeddedParameter().getValue());
    final Range<Float> tailingRange =
        RangeUtils
            .toFloatRange(parameters.getParameter(FeatureFilterParameters.PEAK_TAILINGFACTOR)
                .getEmbeddedParameter().getValue());
    final Range<Float> asymmetryRange =
        RangeUtils.toFloatRange(
            parameters.getParameter(FeatureFilterParameters.PEAK_ASYMMETRYFACTOR)
                .getEmbeddedParameter().getValue());

    // Loop through all rows in feature list
    final ModularFeatureListRow[] rows = newPeakList.getRows()
        .toArray(ModularFeatureListRow[]::new);
    final RawDataFile[] rawdatafiles = newPeakList.getRawDataFiles().toArray(new RawDataFile[0]);
    int totalRawDataFiles = rawdatafiles.length;
    boolean[] keepPeak = new boolean[totalRawDataFiles];
    totalRows = rows.length;
    for (processedRows = 0; !isCanceled() && processedRows < totalRows; processedRows++) {
      final ModularFeatureListRow row = rows[processedRows];

      for (int i = 0; i < totalRawDataFiles; i++) {
        // Peak values
        keepPeak[i] = true;
        final Feature peak = row.getFeature(rawdatafiles[i]);
        // no feature for raw data file
        if (peak == null || peak.getFeatureStatus().equals(FeatureStatus.UNKNOWN)) {
          keepPeak[i] = false;
          continue;
        }

        final double peakDuration = peak.getRawDataPointsRTRange().upperEndpoint()
                                    - peak.getRawDataPointsRTRange().lowerEndpoint();
        final double peakArea = peak.getArea();
        final double peakHeight = peak.getHeight();
        final int peakDatapoints = peak.getScanNumbers().size();
        final Scan msmsScanNumber = peak.getMostIntenseFragmentScan();

        Float peakFWHM = peak.getFWHM();
        Float peakTailingFactor = peak.getTailingFactor();
        Float peakAsymmetryFactor = peak.getAsymmetryFactor();
        if (peakFWHM == null) {
          peakFWHM = -1.0f;
        }
        if (peakTailingFactor == null) {
          peakTailingFactor = -1.0f;
        }
        if (peakAsymmetryFactor == null) {
          peakAsymmetryFactor = -1.0f;
        }

        // Check Duration
        // Check Area
        // Check Height
        // Check # Data Points
        // Check FWHM
        // Check Tailing Factor
        // Check MS/MS filter
        if ((filterByDuration && !durationRange.contains(peakDuration)) ||
            (filterByArea && !areaRange.contains(peakArea)) ||
            (filterByHeight && !heightRange.contains(peakHeight)) ||
            (filterByDatapoints && !datapointsRange.contains(peakDatapoints)) ||
            (filterByFWHM && !fwhmRange.contains(peakFWHM)) ||
            (filterByTailingFactor && !tailingRange.contains(peakTailingFactor)) ||
            (filterByAsymmetryFactor && !asymmetryRange.contains(peakAsymmetryFactor)) ||
            (filterByMS2 && msmsScanNumber != null)) {
          // Mark peak to be removed
          keepPeak[i] = false;
        }
      }
      // empty row?
      boolean isEmpty = Booleans.asList(keepPeak).stream().noneMatch(keep -> keep);
      if (isEmpty) {
        newPeakList.removeRow(row);
      } else {
        for (int i = 0; i < rawdatafiles.length; i++) {
          if (!keepPeak[i]) {
            row.removeFeature(rawdatafiles[i]);
          }
        }
      }
    }

    newPeakList.getAppliedMethods().add(new SimpleFeatureListAppliedMethod(
        FeatureFilterModule.class, parameters));
    return newPeakList;
  }
}
