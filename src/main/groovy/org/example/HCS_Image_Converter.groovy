/*
==============================================================================
HCS Image Converter

Converts compatible HTD-based HCS plate data into organized OME-TIFF files
for downstream analysis.

Version     : 1.1.0
Author      : An-Chi Luo
Affiliation : Imaging Core Facility, College of Medicine,
              National Taiwan University
Date        : 2026-10-28
Main file   : Main_ThreadPool.groovy

Main Workflow
-------------
- Configure the number of worker threads
- Select an HTD dataset and output folder
- Read image and experiment metadata
- Validate image pixel types
- Restore spatial calibration and channel information
- Write image planes to OME-TIFF files

==============================================================================
*/

package org.example

//region for import
import ij.IJ
import ij.gui.DialogListener
import ij.gui.GenericDialog
import ij.io.TiffDecoder
import ij.io.FileInfo
import ij.gui.NonBlockingGenericDialog
import loci.formats.FormatException
import loci.formats.out.OMETiffWriter
import loci.formats.ImageReader
import loci.formats.IFormatReader
import loci.formats.FormatTools
import ome.xml.model.enums.PixelType

import java.awt.AWTEvent
import java.awt.Choice
import java.awt.Dimension
import java.awt.TextField
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.FileStore
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS
import ome.units.quantity.Length
import ome.units.unit.Unit
import ome.xml.model.primitives.Color

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentLinkedQueue
//endregion

static FileInfo fetchTiffMetaData(String filePath){
    File f = new File(filePath)
    TiffDecoder tD = new TiffDecoder(f.getParent(), f.getName())
    FileInfo[] infos = tD.getTiffInfo()
    return infos[0]
}

static def extractInfoFromTifMD(FileInfo info, String[] keyLst) {
    Map tiffMetaData = [:]
    keyLst.eachWithIndex{  itm, int i ->
        String key = "id="+itm
        int keyPos = info.info.indexOf(key)
        if(keyPos <0){
            tiffMetaData[itm] =null
            return
        }
        int valueStart = info.info.indexOf("value=", keyPos)
        if(valueStart <0){
            tiffMetaData[itm] =null
            return
        }
        valueStart += 7
        int valueEnd = info.info.indexOf('"', valueStart )
        if(valueEnd <0){
            tiffMetaData[itm] =null
            return
        }
        tiffMetaData[itm] = info.info.substring(valueStart, valueEnd)
    }
    return tiffMetaData
}

static Unit<Length> convertToUnit(String unit){
    switch (unit.trim().toLowerCase()) {
        case ["um", "µm", "micrometer", "micrometre"]:
            return UNITS.MICROMETER;

        case ["nm", "nanometer", "nanometre"]:
            return UNITS.NANOMETER;

        case ["mm", "millimeter", "millimetre"]:
            return UNITS.MILLIMETER;

        case ["pixel", "pixels", "px"]:
            return UNITS.PIXEL;

        default:
            throw new IllegalArgumentException(
                    "Unsupported length unit: " + unit)
    }
}

static def bitType(int fileType){
    def bitDepthMap = [
            (FileInfo.GRAY8): [8, false, PixelType.UINT8],
            (FileInfo.GRAY10_UNSIGNED): [10, PixelType.UINT16],
            (FileInfo.GRAY12_UNSIGNED): [12, PixelType.UINT16],
            (FileInfo.GRAY16_UNSIGNED): [16, false, PixelType.UINT16],
            (FileInfo.GRAY24_UNSIGNED):[24,false, PixelType.UINT32],
            (FileInfo.GRAY32_FLOAT):[32, true, PixelType.FLOAT],
            (FileInfo.GRAY32_INT):[32, false, PixelType.INT32],
            (FileInfo.GRAY32_UNSIGNED):[32, false, PixelType.UINT32],
            (FileInfo.GRAY64_FLOAT):[64,true, PixelType.DOUBLE]
    ]
    return bitDepthMap[fileType ]
}

static Color getColortype(String channelName){
    String tempChannelName = channelName.toLowerCase()
    if(tempChannelName.contains("dapi")){
        return new Color(0, 0, 255, 255)
    }
    if(tempChannelName.contains("fitc")|| tempChannelName.contains("gfp") || tempChannelName.contains("488")){
        return new Color(0,255,0,255)
    }
    if(tempChannelName.contains("texas red") || tempChannelName.contains("tritc") || tempChannelName.contains("cy3") || tempChannelName.contains("561") ){
        return new Color(255,0,0,255)
    }
    if(tempChannelName.contains("cy5") || tempChannelName.contains("647")){
        return new Color(255,0,255,255)
    }
    return null
}

static String[] getFilePath(IMetadata omeMData, IFormatReader reader, int Zstep, int queryC, int timePoint){
    String[] totalF = reader.getUsedFiles()
    String[] FilterTotalF = totalF.findAll{it -> it.toLowerCase().endsWith('.tif')}
    int zPlaneOffset = Zstep - 1
    int timePointOffset = timePoint - 1
    int channelOffset = queryC - 1
    List<String> seriesF = []
    int wellCount = omeMData.getWellCount(0)
    int siteNo = 0
    int totalZ = reader.getSizeZ()
    int totalT = reader.getSizeT()
    for(int well = 0; well< wellCount; well++){
        siteNo = omeMData.getWellSampleCount(0,well)
        if (siteNo >0){
            break
        }
    }
    int channelCount = reader.getSizeC();
    for (int seriesIndex = 0; seriesIndex < reader.getSeriesCount(); seriesIndex++) {
        int wellIndex = seriesIndex / siteNo;
        int siteIndexInWell = seriesIndex % siteNo;
        int siteOffset = siteIndexInWell * channelCount;
        int wellOffset = wellIndex * siteNo * channelCount;
        int fileIndex = siteOffset + channelOffset + siteNo * channelCount * (zPlaneOffset + timePointOffset) + wellOffset * totalZ * totalT
        //int fileIndex = wellOffset + siteOffset + 1 + Zstep * channelCount * siteNo;
        seriesF.add(FilterTotalF[fileIndex]);
    }
    return seriesF
}

static Length normalizedLength(double value, Unit<Length> unit, int decimals) {
    double um = new Length(value, unit).value(UNITS.MICROMETER).doubleValue()
    double f = Math.pow(10, decimals)
    return new Length(Math.round(um * f) / f, UNITS.MICROMETER)
}
static Long getRequiredSpace(IFormatReader reader){
    int width = reader.getSizeX()
    int height = reader.getSizeY()
    int imageCount = reader.getImageCount()
    int seriesC =  reader.getSeriesCount()
    int bytesPerPixel = FormatTools.getBytesPerPixel(reader.getPixelType())
    long rawBytes = (long) width * height * imageCount * seriesC * bytesPerPixel
    return (long) Math.ceil(rawBytes * 1.2d)
}

static void main (String[] args){
    def runtime = Runtime.getRuntime()
    int maxThread = runtime.availableProcessors() / 2

    List<String> threadLst = []
    Map threadLookUp = [:]
    for(int t = 0; t <= maxThread; t = (t==0? 2:t*2)){
        if(t == 0){
            threadLst.add("Single-threaded")
            threadLookUp["Single-threaded"] = 1
        }
        else{
            threadLst.add( t + " Threads")
            threadLookUp["${t} Threads"] = t
        }
    }

    ///region---------- ask user input  ----------
    String HTDPath
    String outPutPath
    String defaultThread

    int refChannelIndex
    while(true){
        Boolean enoughSpace = null
        Boolean validateHTD = null
        Long requiredSpace = null
        Long usableSpace = null
        String lastHtdPath = null
        String currentHtdPath = null
        String lastOutPut = null
        String currentOutPut = null

        List<String> channelList = []
        NonBlockingGenericDialog askInput = new NonBlockingGenericDialog("HCS Image Converter")
        askInput.addFileField("Input HTD file:", null)
        askInput.addDirectoryField("Output folder: ", null)
        askInput.addChoice("Processing mode:", threadLst as String[], threadLst[-1] )
        askInput.addChoice("Metadata reference channel:", channelList as String[], "", )

        TextField htdField = askInput.getStringFields().get(0) as TextField
        Choice refChannel = askInput.getChoices().get(1) as Choice
        TextField outPutField = askInput.getStringFields().get(1) as TextField

        refChannel.setPreferredSize(
                new Dimension(200, 30)
        )

        askInput.addDialogListener (new DialogListener(){boolean dialogItemChanged(GenericDialog dialog, AWTEvent e) {
            currentHtdPath = htdField.getText()
            currentOutPut = outPutField.getText()
            if(lastHtdPath != currentHtdPath ){
                lastHtdPath = currentHtdPath
                validateHTD = false
                requiredSpace = null
                enoughSpace = null
                channelList.clear()
                refChannel.removeAll()
                if(lastHtdPath.toUpperCase().endsWith(".HTD") &&
                        new File(lastHtdPath).isFile()){
                    try{
                        IFormatReader inputReader = new ImageReader()
                        IMetadata inputOmeMData = MetadataTools.createOMEXMLMetadata()
                        inputReader.setMetadataStore(inputOmeMData)
                        inputReader.setId(lastHtdPath)
                        requiredSpace= getRequiredSpace(inputReader)

                        if (usableSpace != null) {
                            enoughSpace = requiredSpace < usableSpace
                            if (!enoughSpace) {
                                IJ.log("The output location may not have enough disk space.")
                            }
                        }
                        new File(lastHtdPath).eachLine{line ->
                            if (line.contains("WaveName")){
                                channelList.add(line.split(",")[1].replace('"', '').trim())
                            }
                        }
                        channelList.each{it-> refChannel.add(it)}
                        if(!channelList.isEmpty()){
                            refChannel.select(0)

                        }
                        validateHTD = true
                        inputReader.close()
                    }
                    catch(Exception readerE ){
                        //IJ.log("Unable to read HTD: ${readerE.message}")
                        IJ.error("Unable to open HTD dataset",
                                "Bio-Formats could not initialize the selected HTD file. Please check that the HTD file and its associated image files are accessible.")
                    }
                }
            }
            if(currentOutPut != lastOutPut){
                lastOutPut = currentOutPut
                enoughSpace =false
                usableSpace = null
                File outputDir = new File(lastOutPut)
                if(!lastOutPut.isEmpty() && outputDir.isDirectory()){
                    try {
                        FileStore store = Files.getFileStore(Paths.get(lastOutPut))
                        usableSpace = store.getUsableSpace()
                        if (requiredSpace != null) {
                            enoughSpace = requiredSpace < usableSpace
                            if (!enoughSpace) {
                                IJ.log("The output location may not have enough disk space.")
                            }
                        }

                    }
                    catch(Exception storageE){
                        IJ.log("Unable to estimate available storage space.")
                    }

                }
            }
            return true}})

        askInput.showDialog()
        if (askInput.wasCanceled()) {
            IJ.log("The process was terminated early by the user.")
            return
        }

        HTDPath = askInput.getNextString()
        outPutPath = askInput.getNextString()
        defaultThread = askInput.getNextChoice()
        refChannelIndex = channelList.indexOf(askInput.getNextChoice()) + 1

        if(!validateHTD){
            IJ.showMessage("Error" , "Invalid file format.")
            continue
        }

        if(outPutPath == null || outPutPath.trim().isEmpty()){
            IJ.showMessage("Error" , "Please select a folder to save results.")
            continue
        }

        if(!enoughSpace ){
            IJ.showMessage("Error" , "The output location may not have enough disk space.")
            continue
        }

        else{
            break
        }

    }

    File HTDParentPath = new File(HTDPath)
    String HTDParentFileName = HTDParentPath.getParentFile().getName()
    File outPutFile = new File(outPutPath, HTDParentFileName + "_convert")
    outPutFile.mkdirs()
    outPutPath = outPutFile.toString()
    ///endregion---------- ask user input  ----------

    ///region---------- TopReader Initialization ----------
    //ImageJ imageJ = new ImageJ()
    IFormatReader topReader =  new ImageReader()
    IMetadata topOmeMData = MetadataTools.createOMEXMLMetadata()
    topReader.setMetadataStore(topOmeMData)
    topReader.setId(HTDPath)
    int plateCount = topOmeMData.getPlateCount()
    if (plateCount > 1) {
        throw new UnsupportedOperationException(
                "Multiple plates detected. Please export and convert one plate at a time."
        )
    }
    ///endregion---------- TopReader Initialization ----------

    ///region---------- fetch the CoreMetaData ----------
    String plateName = topOmeMData.getPlateName(0)
    int seriesCount = topReader.getSeriesCount()
    int channelCount =  topReader.getSizeC()
    int sizeX  = topReader.getSizeX()
    int sizeY  = topReader.getSizeY()
    int sizeZ  = topReader.getSizeZ()
    int sizeT  = topReader.getSizeT()
    String  DimensionOrder = topReader.getDimensionOrder()
    String pixelType = FormatTools.getPixelTypeString(topReader.getPixelType())
    String[] physicalFPath = getFilePath(topOmeMData, topReader, refChannelIndex,1, 1)


    boolean needZ = topReader.getSizeZ() >1
    ///endregion---------- fetch the CoreMetaData ----------

    ///region---------- Prepare for multiple thread ----------
    int threadCount = threadLookUp[defaultThread] as int
    List<IFormatReader> readerPool = (0..threadCount-1).collect{it ->
        IFormatReader threadReader =  new ImageReader()
        IMetadata tempOMEMData = MetadataTools.createOMEXMLMetadata()
        threadReader.setMetadataStore(tempOMEMData)
        threadReader.setId(HTDPath)
        return threadReader
    }

    ConcurrentLinkedQueue<Integer> seriesQueue = new ConcurrentLinkedQueue()
    seriesCount.times{it ->
        seriesQueue.add(it)
    }

    def pool = Executors.newFixedThreadPool(threadCount)
    List<Future<List<String>>> futures = []
    ///endregion---------- Prepare for multiple thread ----------

    try{
        readerPool.each{reader ->
            Future<List<String>> future = pool.submit ({
                List<String> result = []
                byte [] plane = null

                while (true) {
                    Integer series = seriesQueue.poll() as Integer
                    if (series == null){ break}
                    OMETiffWriter writer = new OMETiffWriter()
                    try{
                        ///region---------- Initialize ----------
                        reader.setSeries(series)
                        IMetadata seriesMData = reader.getMetadataStore()
                        String nameInMD = seriesMData.getImageName(series)

                        Path filePath = Paths.get(physicalFPath[series])
                        FileInfo tiffMData = fetchTiffMetaData(filePath.toString()) ///TIFF Decoder + TiffMetaData ----------
                        ///endregion---------- Initialize ----------

                        ///region----------  Fetch ImageName from TiffMData---------
                        Map sLabelResult = extractInfoFromTifMD(tiffMData, ['"stage-label"'] as String[])
                        String stageLabel = sLabelResult['"stage-label"']
                        stageLabel = stageLabel.replace(":","_")
                        stageLabel = stageLabel.replace(" ","")
                        String imageName = stageLabel ?: nameInMD
                        File seriesOutPutFile = new File(outPutPath, "/"+ plateName + "_" + imageName + ".ome.tif")
                        int samplesPerPixel = tiffMData.samplesPerPixel
                        ///endregion----------  Fetch ImageName from TiffMData---------

                        ///region---------- Prepare output metadata and validate pixel type----------
                        boolean littleEndian = tiffMData.intelByteOrder
                        IMetadata outputMeta = MetadataTools.createOMEXMLMetadata()
                        MetadataTools.populateMetadata(outputMeta, 0, imageName, littleEndian, DimensionOrder, pixelType, sizeX, sizeY, sizeZ, channelCount,sizeT,samplesPerPixel)

                        def bitType = bitType(tiffMData.fileType)
                        if (bitType == null) {
                            throw new UnsupportedOperationException(
                                    "Unsupported TIFF file type: ${tiffMData.fileType}"
                            )
                        }
                        ///endregion---------- Prepare output metadata and validate pixel type----------

                        ///region---------- Restore spatial calibration----------
                        Map queryResults = extractInfoFromTifMD(tiffMData, ['"spatial-calibration-x"', '"spatial-calibration-y"', '"z-position"','"spatial-calibration-units"'] as String[])
                        Length physSizeX = seriesMData.getPixelsPhysicalSizeX(0)
                        Length physSizeY = seriesMData.getPixelsPhysicalSizeY(0)
                        Length physSizeZ = seriesMData.getPixelsPhysicalSizeZ(0)
                        String unitS = queryResults['"spatial-calibration-units"']

                        if(physSizeX == null ){
                            if(queryResults['"spatial-calibration-x"'] != null && unitS!= null){
                                Double pixelWidth = Double.parseDouble( queryResults['"spatial-calibration-x"'] as String)
                                Unit<Length> unit = convertToUnit(unitS)
                                Length actualPhysSizeX = normalizedLength(pixelWidth, unit, 4)
                                outputMeta.setPixelsPhysicalSizeX(actualPhysSizeX, 0)
                            }
                        }
                        else{outputMeta.setPixelsPhysicalSizeX(physSizeX, 0)
                        }
                        if(physSizeY == null ){
                            if(queryResults['"spatial-calibration-y"'] != null && unitS!= null){
                                Double pixelHeight = Double.parseDouble( queryResults['"spatial-calibration-y"'] as String)
                                Unit<Length> unit = convertToUnit(unitS)
                                Length actualPhysSizeY = normalizedLength(pixelHeight, unit, 4)
                                outputMeta.setPixelsPhysicalSizeY(actualPhysSizeY, 0)
                            }
                        }
                        else{outputMeta.setPixelsPhysicalSizeY(physSizeY, 0)
                        }
                        if (physSizeZ != null){
                            outputMeta.setPixelsPhysicalSizeZ(physSizeZ, 0)
                        } else if( needZ ){
                            //region---------- Try to get the Z position of next plane ----------
                            int nextZstep = refChannelIndex +1
                            String [] physicalFPathZ2 = getFilePath(seriesMData, reader, nextZstep, 1, 1)
                            Path filePathZ2 = Paths.get(physicalFPathZ2[series])
                            FileInfo tiffMDataZ2 = fetchTiffMetaData(filePathZ2.toString())
                            def queryResultsZ2 = extractInfoFromTifMD(tiffMDataZ2, ['"z-position"'] as String [])
                            String z1Text = queryResults['"z-position"'] as String
                            String z2Text = queryResultsZ2['"z-position"'] as String
                            //endregion---------- Try to get the Z position of next plane ----------

                            if(z1Text != null && z2Text != null && unitS ){
                                Double pixelDepth = Math.abs(
                                        Double.valueOf(z2Text) - Double.valueOf(z1Text)
                                )
                                if(pixelDepth > 0){
                                    Unit<Length> unit = convertToUnit(unitS)
                                    Length actualPhysSizeZ = normalizedLength(pixelDepth, unit, 3)
                                    outputMeta.setPixelsPhysicalSizeZ(actualPhysSizeZ, 0)
                                }
                            }
                        }
                        ///endregion---------- Restore spatial calibration----------

                        ///region---------- Set channel names and colors ----------
                        channelCount.times{chn ->
                            String chnName = seriesMData.getChannelName(series, chn)
                            if(chnName != null){
                                outputMeta.setChannelName(chnName, 0, chn)
                                if(getColortype(chnName) != null){
                                    outputMeta.setChannelColor(getColortype(chnName), 0, chn)}
                            }
                        }
                        ///endregion---------- Set channel names and colors

                        ///region---------- Write series planes to output file ----------
                        writer.setMetadataRetrieve(outputMeta)
                        int mx = outputMeta.getPixelsSizeX(0)?.getValue() ?: 0
                        int my = outputMeta.getPixelsSizeY(0)?.getValue() ?: 0
                        int rx = reader.getSizeX()
                        int ry = reader.getSizeY()
                        if (mx <= 0 || my <= 0 || rx <= 0 || ry <= 0) {
                            IJ.log("[WARN] ${imageName} series=${series} meta=${mx}x${my} reader=${rx}x${ry} " +
                                    "Z=${outputMeta.getPixelsSizeZ(0)} C=${outputMeta.getPixelsSizeC(0)} " +
                                    "T=${outputMeta.getPixelsSizeT(0)} planes=${reader.getImageCount()}")
                        }

                        writer.setId(seriesOutPutFile.toString())
                        int planeCounts = reader.getImageCount()
                        for(int p = 0; p < planeCounts; p++){
                            if(plane == null){
                                plane = reader.openBytes(p)
                            }
                            else{
                                reader.openBytes(p, plane)
                            }
                            writer.saveBytes(p, plane)
                        }
                        plane = null
                        ///endregion---------- Write series planes to output file ----------

                        result.add( imageName + " is save at : " + outPutPath )

                    }finally {
                        writer.close()
                    }
                }
                return result
            }as Callable<String>)
            futures.add(future)
        }
        def start = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        IJ.log("Starting to convert images at ${start} ====")
        futures.each{future->
            try {
                if(!future.get().isEmpty()){
                    println "Result: ${future.get()}"
                }
            } catch (ExecutionException e) {
                IJ.log("FAILED: ${e.cause}")
            }
        }
        null
    }finally {
        pool.shutdown()
        topReader.close()
        readerPool.each{ reader->
            reader.close()
        }
        def now = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        IJ.log( "==== ALL DONE at ${now} ====")
    }
}




