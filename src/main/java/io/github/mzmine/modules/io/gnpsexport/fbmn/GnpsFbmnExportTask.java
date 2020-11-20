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
/*
 * This module was prepared by Abi Sarvepalli, Christopher Jensen, and Zheng Zhang at the Dorrestein
 * Lab (University of California, San Diego).
 * 
 * It is freely available under the GNU GPL licence of MZmine2.
 * 
 * For any questions or concerns, please refer to:
 * https://groups.google.com/forum/#!forum/molecular_networking_bug_reports
 * 
 * Credit to the Du-Lab development team for the initial commitment to the MGF export module.
 */

package io.github.mzmine.modules.io.gnpsexport.fbmn;

import io.github.mzmine.datamodel.data.Feature;
import io.github.mzmine.datamodel.data.FeatureList;
import io.github.mzmine.datamodel.data.FeatureListRow;
import io.github.mzmine.datamodel.data.ModularFeature;
import io.github.mzmine.datamodel.data.ModularFeatureList;
import io.github.mzmine.datamodel.data.ModularFeatureListRow;
import io.github.mzmine.util.FeatureUtils;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;

import io.github.mzmine.datamodel.DataPoint;
import io.github.mzmine.datamodel.MassList;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.tools.msmsspectramerge.MergedSpectrum;
import io.github.mzmine.modules.tools.msmsspectramerge.MsMsSpectraMergeModule;
import io.github.mzmine.modules.tools.msmsspectramerge.MsMsSpectraMergeParameters;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.taskcontrol.AbstractTask;
import io.github.mzmine.taskcontrol.TaskStatus;

/**
 * Exports all files needed for GNPS
 * 
 * @author Robin Schmid (robinschmid@uni-muenster.de)
 *
 */
public class GnpsFbmnExportTask extends AbstractTask {
  private final FeatureList[] featureLists;
  private final File fileName;
  private final String plNamePattern = "{}";
  private int currentIndex = 0;
  private final MsMsSpectraMergeParameters mergeParameters;

  private final String massListName;

  GnpsFbmnExportTask(ParameterSet parameters) {
    this.featureLists = parameters.getParameter(GnpsFbmnExportAndSubmitParameters.FEATURE_LISTS)
        .getValue().getMatchingPeakLists();

    this.fileName = parameters.getParameter(GnpsFbmnExportAndSubmitParameters.FILENAME).getValue();

    this.massListName =
        parameters.getParameter(GnpsFbmnExportAndSubmitParameters.MASS_LIST).getValue();

    if (parameters.getParameter(GnpsFbmnExportAndSubmitParameters.MERGE_PARAMETER).getValue()) {
      mergeParameters = parameters.getParameter(GnpsFbmnExportAndSubmitParameters.MERGE_PARAMETER)
          .getEmbeddedParameters();
    } else {
      mergeParameters = null;
    }
  }

  @Override
  public double getFinishedPercentage() {
    if (featureLists.length == 0)
      return 1;
    else
      return currentIndex / featureLists.length;
  }

  @Override
  public void run() {
    setStatus(TaskStatus.PROCESSING);

    // Shall export several files?
    boolean substitute = fileName.getPath().contains(plNamePattern);

    // Process feature lists
    for (FeatureList featureList : featureLists) {
      currentIndex++;

      // Filename
      File curFile = fileName;
      if (substitute) {
        // Cleanup from illegal filename characters
        String cleanPlName = featureList.getName().replaceAll("[^a-zA-Z0-9.-]", "_");
        // Substitute
        String newFilename =
            fileName.getPath().replaceAll(Pattern.quote(plNamePattern), cleanPlName);
        curFile = new File(newFilename);
      }

      // Open file
      FileWriter writer;
      try {
        writer = new FileWriter(curFile);
      } catch (Exception e) {
        setStatus(TaskStatus.ERROR);
        setErrorMessage("Could not open file " + curFile + " for writing.");
        return;
      }

      try {
        export(featureList, writer, curFile);
      } catch (IOException e) {
        setStatus(TaskStatus.ERROR);
        setErrorMessage("Error while writing into file " + curFile + ": " + e.getMessage());
        return;
      }

      // Cancel?
      if (isCanceled()) {
        return;
      }

      // Close file
      try {
        writer.close();
      } catch (Exception e) {
        setStatus(TaskStatus.ERROR);
        setErrorMessage("Could not close file " + curFile);
        return;
      }

      // If feature list substitution pattern wasn't found,
      // treat one feature list only
      if (!substitute)
        break;
    }

    if (getStatus() == TaskStatus.PROCESSING)
      setStatus(TaskStatus.FINISHED);
  }

  private void export(FeatureList featureList, FileWriter writer, File curFile) throws IOException {
    final String newLine = System.lineSeparator();

    for (FeatureListRow row : featureList.getRows()) {
      String rowID = Integer.toString(row.getID());

      String retTimeInSeconds = Double.toString(Math.round(row.getAverageRT() * 60 * 100) / 100.);
      // Get the MS/MS scan number
      Feature bestFeature = row.getBestFeature();
      if (bestFeature == null)
        continue;
      int msmsScanNumber = bestFeature.getMostIntenseFragmentScanNumber();
      if (rowID != null) {
        FeatureListRow copyRow = copyFeatureRow(row);
        // Best feature always exists, because feature list row has at
        // least one feature
        bestFeature = copyRow.getBestFeature();

        // Get the MS/MS scan number

        msmsScanNumber = bestFeature.getMostIntenseFragmentScanNumber();
        while (msmsScanNumber < 1) {
          copyRow.removeFeature(bestFeature.getRawDataFile());
          if (copyRow.getFeatures().size() == 0)
            break;

          bestFeature = copyRow.getBestFeature();
          msmsScanNumber = bestFeature.getMostIntenseFragmentScanNumber();
        }
      }
      if (msmsScanNumber >= 1) {
        // MS/MS scan must exist, because msmsScanNumber was > 0
        Scan msmsScan = bestFeature.getRawDataFile().getScan(msmsScanNumber);

        MassList massList = msmsScan.getMassList(massListName);

        if (massList == null) {
          MZmineCore.getDesktop().displayErrorMessage("There is no mass list called " + massListName
              + " for MS/MS scan #" + msmsScanNumber + " (" + bestFeature.getRawDataFile() + ")");
          return;
        }

        writer.write("BEGIN IONS" + newLine);

        if (rowID != null)
          writer.write("FEATURE_ID=" + rowID + newLine);

        String mass = Double.toString(Math.round(row.getAverageMZ() * 10000) / 10000.);
        if (mass != null)
          writer.write("PEPMASS=" + mass + newLine);

        if (rowID != null) {
          writer.write("SCANS=" + rowID + newLine);
          writer.write("RTINSECONDS=" + retTimeInSeconds + newLine);
        }

        int msmsCharge = msmsScan.getPrecursorCharge();
        String msmsPolarity = msmsScan.getPolarity().asSingleChar();
        if (msmsPolarity.equals("0"))
          msmsPolarity = "";
        if (msmsCharge == 0) {
          msmsCharge = 1;
          msmsPolarity = "";
        }
        writer.write("CHARGE=" + msmsCharge + msmsPolarity + newLine);

        writer.write("MSLEVEL=2" + newLine);
        DataPoint[] dataPoints = massList.getDataPoints();
        if (mergeParameters != null) {
          MsMsSpectraMergeModule merger =
              MZmineCore.getModuleInstance(MsMsSpectraMergeModule.class);
          MergedSpectrum spectrum =
              merger.getBestMergedSpectrum(mergeParameters, row, massListName);
          if (spectrum != null) {
            dataPoints = spectrum.data;
            writer.write("MERGED_STATS=");
            writer.write(spectrum.getMergeStatsDescription());
            writer.write(newLine);
          }
        }
        for (DataPoint feature : dataPoints) {
          writer.write(feature.getMZ() + " " + feature.getIntensity() + newLine);
        }

        writer.write("END IONS" + newLine);
        writer.write(newLine);
      }
    }

  }

  public String getTaskDescription() {
    return "Exporting GNPS of feature list(s) " + Arrays.toString(featureLists) + " to MGF file(s)";
  }

  /**
   * Create a copy of a feature list row.
   */
  private static FeatureListRow copyFeatureRow(final FeatureListRow row) {
    // Copy the feature list row.
    final FeatureListRow newRow = new ModularFeatureListRow(
        (ModularFeatureList) row.getFeatureList(), row.getID());
    FeatureUtils.copyFeatureListRowProperties(row, newRow);

    // Copy the features.
    for (final Feature feature : row.getFeatures()) {

      final Feature newFeature = new ModularFeature(feature);
      FeatureUtils.copyPeakProperties(feature, newFeature);
      newRow.addFeature(feature.getRawDataFile(), newFeature);

    }

    return newRow;
  }

}
