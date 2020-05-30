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

package io.github.mzmine.modules.io.mztabmimport;

import com.google.common.collect.Range;
import io.github.mzmine.datamodel.*;
import io.github.mzmine.datamodel.impl.*;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.io.rawdataimport.RawDataImportModule;
import io.github.mzmine.modules.io.rawdataimport.RawDataImportParameters;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.UserParameter;
import io.github.mzmine.parameters.parametertypes.StringParameter;
import io.github.mzmine.taskcontrol.AbstractTask;
import io.github.mzmine.taskcontrol.Task;
import io.github.mzmine.taskcontrol.TaskStatus;
import de.isas.mztab2.io.*;
import de.isas.mztab2.model.*;
import javafx.application.Platform;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class MzTabmImportTask extends AbstractTask {

    // parameter values
    private MZmineProject project;
    private File inputFile;
    private boolean importRawFiles;
    private double finishedPercentage = 0.0;

    // underlying tasks for importing raw data
    private final List<Task> underlyingTasks = new ArrayList<Task>();

    MzTabmImportTask(MZmineProject project, ParameterSet parameters, File inputFile){
        this.project = project;
        this.inputFile = inputFile;
        this.importRawFiles = parameters.getParameter(MzTabmImportParameters.importRawFiles).getValue();
    }

    @Override
    public String getTaskDescription() {
        return "Loading feature list from mzTab-m file " + inputFile;
    }

    @Override
    public void cancel() {
        super.cancel();
        // Cancel all the data import tasks
        for (Task t : underlyingTasks) {
            if ((t.getStatus() == TaskStatus.WAITING) || (t.getStatus() == TaskStatus.PROCESSING))
                t.cancel();
        }
    }

    @Override
    public double getFinishedPercentage() {
        if (importRawFiles && (getStatus() == TaskStatus.PROCESSING) && (!underlyingTasks.isEmpty())) {
            double newPercentage = 0.0;
            synchronized (underlyingTasks) {
                for (Task t : underlyingTasks) {
                    newPercentage += t.getFinishedPercentage();
                }
                newPercentage /= underlyingTasks.size();
            }
            // Let's say that raw data import takes 80% of the time
            finishedPercentage = 0.1 + newPercentage * 0.8;
        }
        return finishedPercentage;
    }

    @Override
    public void run() {
        setStatus(TaskStatus.PROCESSING);
        try {

            //Load mzTab file
            MzTabFileParser mzTabmFileParser​ = new MzTabFileParser(inputFile);
            mzTabmFileParser​.parse(System.err, MZTabErrorType.Level.Info, 500);

            //inspect the output of the parse and errors
            MZTabErrorList errors = mzTabmFileParser​.getErrorList();

            //converting the MZTabErrorList into a list of ValidationMessage
            List<ValidationMessage> messages = errors.convertToValidationMessages();

            if(!errors.isEmpty()){
                setStatus(TaskStatus.ERROR);
                setErrorMessage(
                        "Error processing"+ inputFile + ":\n"+mzTabmFileParser​.getErrorList().toString()
                );
                return;
            }
            MzTab mzTabFile = mzTabmFileParser​.getMZTabFile();

            //Let's say initial parsing took 10% of the time
            finishedPercentage = 0.1;

            //Import raw data files
            List<RawDataFile> rawDataFiles = importRawDataFiles(mzTabFile);

            // Check if not canceled
            if (isCanceled())
                return;

            //Create new feature list
            String peakListName = inputFile.getName().replace(".mzTab","");
            RawDataFile rawDataArray[] = rawDataFiles.toArray(new RawDataFile[0]);
            PeakList newPeakList = new SimplePeakList(peakListName,rawDataArray);

            // Check if not canceled
            if (isCanceled())
                return;

            // Import variables
            importVariables(mzTabFile, rawDataFiles);

            // Check if not canceled
            if (isCanceled())
                return;
            // import small molecules (=feature list rows)
            importSummaryTable(newPeakList, mzTabFile, rawDataFiles);

            // Check if not canceled
            if (isCanceled())
                return;

            // Add the new feature list to the project
            project.addPeakList(newPeakList);

            // Finish
            setStatus(TaskStatus.FINISHED);
            finishedPercentage = 1.0;




        }
        catch (Exception e){
            e.printStackTrace();
            setStatus(TaskStatus.ERROR);
            setErrorMessage("Could not import data from " + inputFile + ": " + e.getMessage());
            return;
        }
    }

    private List<RawDataFile> importRawDataFiles(MzTab mzTabmFile) throws Exception{
        List<MsRun> msrun = mzTabmFile.getMetadata().getMsRun();
        List<RawDataFile> rawDataFiles = new ArrayList<RawDataFile>();
        //Used in getting reference for files imported from file name
        String filesNameprefix = null;
        //if Import option is selected in parameters window
        if(importRawFiles){
            List<File> filesToImport = new ArrayList<>();
            for(MsRun singleRun : msrun){
                File fileToImport = new File(singleRun.getLocation());
                if(fileToImport.exists() && fileToImport.canRead()){
                    filesToImport.add(fileToImport);
                }
                else{
                    //Check if the raw file exists in the same folder as the
                    //mzTab file
                    File checkFile = new File(inputFile.getParentFile(),fileToImport.getName());
                    if(checkFile.exists() && checkFile.canRead()){
                        filesToImport.add(checkFile);
                    }
                    else{
                        // Append .gz & check again if file exists as a
                        // workaround to .gz not getting preserved
                        // when .mzML.gz importing
                        checkFile = new File(inputFile.getParentFile(), fileToImport.getName() + ".gz");
                        if (checkFile.exists() && checkFile.canRead())
                            filesToImport.add(checkFile);
                        else {
                            // One more level of checking, appending .zip &
                            // checking as a workaround
                            checkFile = new File(inputFile.getParentFile(), fileToImport.getName() + ".zip");
                            if (checkFile.exists() && checkFile.canRead())
                                filesToImport.add(checkFile);
                        }

                    }
                }

            }
            RawDataImportModule RDI = MZmineCore.getModuleInstance(RawDataImportModule.class);
            ParameterSet rdiParameters = RDI.getParameterSetClass().getDeclaredConstructor().newInstance();
            rdiParameters.getParameter(RawDataImportParameters.fileNames).setValue(filesToImport.toArray(new File[0]));
            synchronized (underlyingTasks){
                final CountDownLatch doneLatch = new CountDownLatch(1);
                Platform.runLater(()->{
                    RDI.runModule(project, rdiParameters, underlyingTasks);
                    doneLatch.countDown();
                });
                // wait for fx thread
                doneLatch.await();
            }
            filesNameprefix = RDI.getLastCommonPrefix();
            //import files
            if(underlyingTasks.size()>0){
                MZmineCore.getTaskController().addTasks(underlyingTasks.toArray(new Task[0]));
            }

            // Wait until all raw data file imports have completed
            while (true) {
                if (isCanceled())
                    return null;
                boolean tasksFinished = true;
                for (Task task : underlyingTasks) {
                    if ((task.getStatus() == TaskStatus.WAITING)
                            || (task.getStatus() == TaskStatus.PROCESSING))
                        tasksFinished = false;
                }
                if (tasksFinished)
                    break;
                Thread.sleep(1000);
            }
        }
        else{
            finishedPercentage = 0.5;
        }

        // Find a matching RawDataFile for each msRun object
        for(MsRun singleRun: msrun){
            String rawFileName = new File(singleRun.getLocation()).getName();
            RawDataFile rawDataFile = null;
            //check whether we already have raw data file of that name
            for(RawDataFile f: project.getDataFiles()){
                String prefixedFileName = filesNameprefix+f.getName();
                if(f.getName().equals(rawFileName)||prefixedFileName.equals(rawFileName)){
                    rawDataFile = f;
                    break;
                }
            }

            //if no data file of that name exist, create a new one
            if(rawDataFile == null){
                RawDataFileWriter writer = MZmineCore.createNewFile(rawFileName);
                rawDataFile = writer.finishWriting();
                project.addFile(rawDataFile);
            }

            //Save a reference to the new raw data file
            rawDataFiles.add(rawDataFile);
        }
        return rawDataFiles;
    }

    private void importVariables(MzTab mzTabmFile,List<RawDataFile> rawDataFiles){
        //Add sample parameters if available in mzTab-m file
        List<StudyVariable> variableMap = mzTabmFile.getMetadata().getStudyVariable();
        if(variableMap.isEmpty())
            return;
        UserParameter<?,?> newUserParameter = new StringParameter(inputFile.getName()+" study variable", "");
        project.addParameter(newUserParameter);
        for(StudyVariable studyVariable:variableMap){
            // Stop the process if cancel() was called
            if(isCanceled())
                return;

            List<Assay> assayList = studyVariable.getAssayRefs();
            for(int i=0;i<assayList.size();i++){
                Assay dataFileAssay = assayList.get(i);
                if(dataFileAssay != null){
                    int indexOfAssay = mzTabmFile.getMetadata().getAssay().indexOf(dataFileAssay);
                    project.setParameterValue(newUserParameter, rawDataFiles.get(indexOfAssay), studyVariable.getDescription());
                }
            }
        }
    }

    private void importSummaryTable(PeakList newPeakList, MzTab mzTabmFile, List<RawDataFile> rawDataFiles){
        List<Assay> assayList = mzTabmFile.getMetadata().getAssay();
        List<SmallMoleculeSummary> smallMoleculeSummaryList =  mzTabmFile.getSmallMoleculeSummary();

        //Loop through SML data
        String formula,description,method, url="";
        double mzExp = 0, abundance = 0, peak_mz = 0, peak_rt = 0, peak_height = 0, rtValue = 0;
        // int charge = 0;
        int rowCounter = 0;
        List <SmallMoleculeFeature> smfs = mzTabmFile.getSmallMoleculeFeature();

        for (SmallMoleculeSummary smallMoleculeSummary: smallMoleculeSummaryList){
            //Stop the process if cancel() is called
            if(isCanceled())
                return;

            rowCounter++;
            //TODO multiple values support in MZmine required
            formula = smallMoleculeSummary.getChemicalFormula().get(0);
//            List<String> smile = smallMoleculeSummary.getSmiles();
//            List<String> inchiKey = smallMoleculeSummary.getInchi();
            description = smallMoleculeSummary.getChemicalName().get(0);
//             species = smallMoleculeSummary.getSpecies();?
            Database db = mzTabmFile.getMetadata().getDatabase().get(0);
            method = db.getPrefix()+'@'+db.getVersion();
//             String reliability = smallMoleculeSummary.getReliability();

            if(smallMoleculeSummary.getUri().size() != 0){
                url = smallMoleculeSummary.getUri().get(0);
            }

//            TODO modifications
            String identifier = smallMoleculeSummary.getDatabaseIdentifier().get(0);
//            Average Retention Time
            List <Integer> smfIdRefs = smallMoleculeSummary.getSmfIdRefs();
            for(SmallMoleculeFeature smf : smfs){
                if(smf.getSmfId().equals(smfIdRefs.get(0))){
                    rtValue = smf.getRetentionTimeInSeconds();
                }
            }

            if((url != null )&& (url.equals("null"))){
                url = null;
            }
            if(identifier.equals("null")){
                identifier = null;
            }
            if(description == null && identifier!=null){
                description = identifier;
            }

            // Add shared information to row
            SimplePeakListRow newRow = new SimplePeakListRow(rowCounter);
            newRow.setAverageMZ(mzExp);
            newRow.setAverageRT(rtValue);
            if (description != null) {
                SimplePeakIdentity newIdentity =
                        new SimplePeakIdentity(description, formula, method, identifier, url);
                newRow.addPeakIdentity(newIdentity, false);
            }

            // Add raw data file entries to row
            for(int i=0;i<rawDataFiles.size();i++){
                Assay dataFileAssay =  assayList.get(i);
                RawDataFile rawData = rawDataFiles.get(i);
                abundance = 0;
                peak_mz = 0;
                peak_rt = 0;
                peak_height = 0;

                if(smallMoleculeSummary.getAbundanceAssay().get(i) != null){
                    abundance = smallMoleculeSummary.getAbundanceAssay().get(i);
                }
                List<OptColumnMapping> optColList =  smallMoleculeSummary.getOpt();
                String assayName = dataFileAssay.getName();
                boolean hasAssayIdentifier = false;
                //TODO sout dataFileAssay.getName(); //to check whether dataFileAssay.getName().equals(optCol.getIdentifier() is correct or not
                peak_mz = mzExp;
                peak_rt = rtValue;
                for(OptColumnMapping optCol: optColList){
                    if(dataFileAssay.getName().equals(optCol.getIdentifier()) && optCol.getParam().getName().equals("peak_mz")){
                        peak_mz = Double.parseDouble(optCol.getParam().getValue());
                    }
                    if(dataFileAssay.getName().equals(optCol.getIdentifier()) && optCol.getParam().getName().equals("peak_rt")){
                        peak_rt = Double.parseDouble(optCol.getParam().getValue());
                    }
                    if(dataFileAssay.getName().equals(optCol.getIdentifier()) && optCol.getParam().getName().equals("peak_height")){
                        peak_height = Double.parseDouble(optCol.getParam().getValue());
                    }
                }

                int scanNumbers[] = {};
                DataPoint finalDataPoint[] = new DataPoint[1];
                finalDataPoint[0] = new SimpleDataPoint(peak_mz,peak_height);
                int representativeScan = 0;
                int fragmentScan = 0;
                int[] allFragmentScans = new int[] {0};

                Range<Double> finalRTRange = Range.singleton(peak_rt);
                Range<Double> finalMZRange = Range.singleton(peak_mz);
                Range<Double> finalIntensityRange = Range.singleton(peak_height);
                FeatureStatus status = FeatureStatus.DETECTED;

                Feature peak = new SimpleFeature(rawData, peak_mz, peak_rt, peak_height, abundance,
                        scanNumbers, finalDataPoint, status, representativeScan, fragmentScan, allFragmentScans,
                        finalRTRange, finalMZRange, finalIntensityRange);

                if (abundance > 0) {
                    newRow.addPeak(rawData, peak);
                }
            }

            // Add row to feature list
            newPeakList.addRow(newRow);

        }

    }


}
