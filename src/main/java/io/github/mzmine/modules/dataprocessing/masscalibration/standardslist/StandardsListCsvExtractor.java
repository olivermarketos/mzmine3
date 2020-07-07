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

package io.github.mzmine.modules.dataprocessing.masscalibration.standardslist;

import com.Ostermiller.util.CSVParser;
import com.Ostermiller.util.LabeledCSVParser;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Logger;


/**
 * StandardsListExtractor for csv files
 * expects fixed column names
 * 'Retention time (min)' and 'Ion formula'
 * for storing needed data
 */
public class StandardsListCsvExtractor implements StandardsListExtractor {

  protected static final String retentionTimeColumnName = "Retention time (min)";
  protected static final String molecularFormulaColumnName = "Ion formula";

  protected Logger logger = Logger.getLogger(this.getClass().getName());

  protected String filename;

  protected LabeledCSVParser csvReader;

  protected ArrayList<StandardsListItem> extractedData;

  /**
   * Creates the extractor
   *
   * @param filename csv filename
   * @throws IOException exception thrown when issues opening given file occur
   */
  public StandardsListCsvExtractor(String filename) throws IOException {
    this.filename = filename;
    this.csvReader = new LabeledCSVParser(new CSVParser(new FileInputStream(filename)));
  }

  /**
   * Extracts standards list caching underlying list data
   *
   * @return new standards list object
   * @throws RuntimeException thrown when required column names are missing
   * @throws IOException      thrown when issues extracting from the file occur
   */
  public StandardsList extractStandardsList() throws IOException {
    logger.fine("Extracting standards list " + filename);

    if (this.extractedData != null) {
      logger.fine("Using cached list");
      return new StandardsList(this.extractedData);
    }
    this.extractedData = new ArrayList<StandardsListItem>();

    while (csvReader.getLine() != null) {
      String retentionTimeString = csvReader.getValueByLabel(retentionTimeColumnName);
      String molecularFormula = csvReader.getValueByLabel(molecularFormulaColumnName);
      if (retentionTimeString == null || molecularFormula == null) {
        throw new RuntimeException(String.format("Csv file %s missing retention time [%s]"
                + " or molecular formula [%s] columns", filename, retentionTimeColumnName, molecularFormulaColumnName));
      }

      try {
        double retentionTime = Double.valueOf(retentionTimeString);
        extractedData.add(new StandardsListItem(molecularFormula, retentionTime));
      } catch (Exception e) {
        logger.fine("Exception occurred when reading row index " + csvReader.getLastLineNumber());
        logger.fine(e.toString());
      }
    }

    logger.info("Extracted " + extractedData.size() + " standard molecules from "
            + csvReader.getLastLineNumber() + " rows");
    if (extractedData.size() < csvReader.getLastLineNumber()) {
      logger.warning("Skipped " + (csvReader.getLastLineNumber() - extractedData.size())
              + " rows when reading standards list in csv file " + filename);
    }

    return new StandardsList(extractedData);
  }
}
