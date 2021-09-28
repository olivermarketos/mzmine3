/*
 * Copyright 2006-2021 The MZmine Development Team
 *
 * This file is part of MZmine.
 *
 * MZmine is free software; you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * MZmine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with MZmine; if not,
 * write to the Free Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

package io.github.mzmine.modules.io.projectload.version_3_0;

import io.github.mzmine.datamodel.MZmineProject;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.features.FeatureList.FeatureListAppliedMethod;
import io.github.mzmine.datamodel.features.ModularFeatureList;
import io.github.mzmine.datamodel.features.ModularFeatureListRow;
import io.github.mzmine.datamodel.features.SimpleFeatureListAppliedMethod;
import io.github.mzmine.datamodel.features.types.numbers.IDType;
import io.github.mzmine.modules.io.projectsave.FeatureListSaveTask;
import io.github.mzmine.taskcontrol.AbstractTask;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.MemoryMapStorage;
import io.github.mzmine.util.ParsingUtils;
import io.github.mzmine.util.ZipUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.XMLEvent;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class FeatureListLoadTask extends AbstractTask {

  public static final String TEMP_FLIST_DATA_FOLDER = "mzmine_featurelists_temp";
  public static final Pattern fileNamePattern = Pattern
      .compile("([^\\n]+)(" + FeatureListSaveTask.DATA_FILE_SUFFIX + ")");

  private static final Logger logger = Logger.getLogger(FeatureListLoadTask.class.getName());

  private final ZipFile zip;
  private final MZmineProject project;
  private int totalRows = 0;
  private int processedRows = 0;
  private final AtomicInteger rowCounter = new AtomicInteger(0);

  public FeatureListLoadTask(@NotNull MemoryMapStorage storage, @NotNull MZmineProject project,
      ZipFile zip) {
    super(storage, new Date());
    this.project = project;
    this.zip = zip;
  }

  @Override
  public String getTaskDescription() {
    return null;
  }

  @Override
  public double getFinishedPercentage() {
    return 0;
  }

  @Override
  public void run() {
    setStatus(TaskStatus.PROCESSING);
    try {
      Path tempDirectory = Files.createTempDirectory(TEMP_FLIST_DATA_FOLDER);

      logger.info(() -> "Unzipping feature lists of project to " + tempDirectory.toString());
      ZipUtils.unzipDirectory(FeatureListSaveTask.FLIST_FOLDER, zip, tempDirectory.toFile());
      logger.info(() -> "Unzipping feature lists done.");

      File[] files = tempDirectory.toFile()
          .listFiles((dir, name) -> fileNamePattern.matcher(name).matches());

      final MemoryMapStorage storage = MemoryMapStorage.forFeatureList();

      for (File flistFile : files) {

        final File metadataFile = new File(flistFile.toString()
            .replace(FeatureListSaveTask.DATA_FILE_SUFFIX,
                FeatureListSaveTask.METADATA_FILE_SUFFIX));
        ModularFeatureList flist = createRows(storage, flistFile, metadataFile);
        if (flist == null) {
          logger.severe(
              () -> "Cannot load feature list from files " + flistFile.getAbsolutePath() + " and "
                  + metadataFile.getAbsolutePath());
        }
        parseFeatureList(storage, flist, flistFile);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void parseFeatureList(MemoryMapStorage storage, ModularFeatureList flist,
      File flistFile) {
    try (InputStream fis = new FileInputStream(flistFile)) {
      final XMLInputFactory xif = XMLInputFactory.newInstance();
      final XMLStreamReader reader = xif.createXMLStreamReader(fis);

      while (reader.hasNext()) {
        int type = reader.next();
        switch (type) {
          case XMLEvent.START_ELEMENT -> {
            switch (reader.getLocalName()) {
              case CONST.XML_FEATURE_LIST_ELEMENT -> {
                if (!flist.getName().equals(reader.getText())) {
                  throw new IllegalArgumentException(
                      "Feature list names do not match. " + flist.getName() + " " + reader
                          .getText());
                }
              }
              case CONST.XML_ROW_ELEMENT -> {
                parseRow(reader, storage, flist);
              }
            }
          }
          default -> {
          }
        }
      }

    } catch (IOException | XMLStreamException e) {
      logger.warning(() -> "Error opening file " + flistFile.getAbsolutePath());
    }
  }

  private ModularFeatureList createRows(MemoryMapStorage storage, File dataFile,
      File metadataFile) {

    ModularFeatureList flist = readMetadataCreateFeatureList(dataFile, storage);
    if (flist == null) {
      throw new IllegalStateException("Cannot create feature list.");
    }

    final String idTypeUniqueID = new IDType().getUniqueID();

    try (InputStream fis = new FileInputStream(dataFile)) {
      final XMLInputFactory xif = XMLInputFactory.newInstance();
      final XMLStreamReader reader = xif.createXMLStreamReader(fis);

      logger.finest(
          () -> "Creating " + ModularFeatureListRow.class.getSimpleName() + "s for feature list "
              + flist.getName() + ".");
      while (reader.hasNext()) {
        final int type = reader.next();
        if (type == XMLEvent.START_ELEMENT && reader.getLocalName().equals(CONST.XML_ROW_ELEMENT)) {
          int id = Integer.parseInt(reader.getAttributeValue(null, idTypeUniqueID));
          flist.addRow(new ModularFeatureListRow(flist, id));
        }
      }
    } catch (IOException | XMLStreamException e) {
      logger.warning(() -> "Error opening file " + dataFile.getAbsolutePath());
      return null;
    }

    logger.finest(
        () -> "Created " + flist.getNumberOfRows() + " rows in feature list " + flist.getName());
    return flist;
  }

  private ModularFeatureList readMetadataCreateFeatureList(File file, MemoryMapStorage storage) {
    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();

      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document configuration = dBuilder.parse(file);

      XPathFactory factory = XPathFactory.newInstance();
      XPath xpath = factory.newXPath();

      XPathExpression metadataExpr = xpath
          .compile("//" + CONST.XML_ROOT_ELEMENT + "/" + CONST.XML_FLIST_METADATA_ELEMENT);
      final Element metadataElement = (Element) (((NodeList) metadataExpr
          .evaluate(file, XPathConstants.NODESET)).item(0));

      final ModularFeatureList flist = new ModularFeatureList(
          metadataElement.getElementsByTagName(CONST.XML_FLIST_NAME_ELEMENT).item(0)
              .getTextContent(), storage, new ArrayList());

      flist.setDateCreated(
          metadataElement.getElementsByTagName(CONST.XML_FLIST_DATE_CREATED_ELEMENT).item(0)
              .getTextContent());

      XPathExpression expr = xpath
          .compile("//" + CONST.XML_ROOT_ELEMENT + "/" + CONST.XML_APPLIED_METHODS_LIST_ELEMENT);
      NodeList nodelist = (NodeList) expr.evaluate(file, XPathConstants.NODESET);
      if (nodelist.getLength() != 1) {
        throw new IllegalArgumentException(
            "XML file " + file + " does not have an applied methods element.");
      }
      // set applied methods
      Element appliedMethodsList = (Element) nodelist.item(0);
      NodeList methodElements = appliedMethodsList
          .getElementsByTagName(CONST.XML_APPLIED_METHOD_ELEMENT);
      for (int i = 0; i < methodElements.getLength(); i++) {
        FeatureListAppliedMethod method = SimpleFeatureListAppliedMethod
            .loadValueFromXML((Element) methodElements.item(i));
        flist.getAppliedMethods().add(method);
      }

      XPathExpression rawFilesListExpr = xpath
          .compile("//" + CONST.XML_ROOT_ELEMENT + "/" + CONST.XML_RAW_FILES_LIST_ELEMENT);
      nodelist = (NodeList) rawFilesListExpr.evaluate(file, XPathConstants.NODESET);

      // set selected scans
      for (int i = 0; i < nodelist.getLength(); i++) {
        Element fileElement = (Element) nodelist.item(0);
        String name = fileElement.getAttribute(CONST.XML_RAW_FILE_NAME_ATTR);
        String path = fileElement.getTextContent();
        Optional<RawDataFile> f = Arrays.stream(project.getDataFiles()).filter(
            r -> r.getName().equals(name) && (
                path.isEmpty() && path.equals("null") && r.getAbsolutePath() != null ? true
                    : r.getAbsolutePath().equals(path))).findFirst();
        if (!f.isPresent()) {
          throw new IllegalStateException("Raw data file with name " + name + " and path " + path
              + " not imported to project.");
        }
        flist.getRawDataFiles().add(f.get());

        final Element selectedScansElement = (Element) fileElement
            .getElementsByTagName(CONST.XML_SELECTED_SCANS_ELEMENT).item(0);
        final int[] selectedScanIndices = ParsingUtils
            .stringToIntArray(selectedScansElement.getTextContent());
        List<Scan> selectedScans = ParsingUtils
            .getSublistFromIndices(f.get().getScans(), selectedScanIndices);
        flist.setSelectedScans(f.get(), selectedScans);

        return flist;
      }
    } catch (XPathExpressionException | ParserConfigurationException | SAXException | IOException e) {
      e.printStackTrace();
      logger.log(Level.SEVERE, e.getMessage(), e);
      return null;
    }
    return null;
  }

  private void parseRow(XMLStreamReader reader, MemoryMapStorage storage,
      ModularFeatureList flist) {
    if(!reader.getLocalName().equals(CONST.XML_ROW_ELEMENT)) {
      throw new IllegalStateException("Cannot parse row if current element is not a row element");
    }
  }
}
