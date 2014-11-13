/*
 * Copyright (C) 2014 ddecap
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package be.ugent.intec.halvade.tools;

import be.ugent.intec.halvade.hadoop.mapreduce.HalvadeCounters;
import be.ugent.intec.halvade.utils.CommandGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import be.ugent.intec.halvade.utils.ProcessBuilderWrapper;
import be.ugent.intec.halvade.utils.Logger;
import be.ugent.intec.halvade.utils.MyConf;
import java.text.DecimalFormat;
import java.util.List;
import org.apache.hadoop.mapreduce.Reducer;

/**
 *
 * @author ddecap
 */
public class GATKTools {
    // params
    String reference;
    String bin;
    String gatk;
    String java;
    String mem = "-Xmx2g";
    String[] variantCaller = {
        "HaplotypeCaller",
        "UnifiedGenotyper"};
    int threadingType = 0; // 0 = data multithreading, 1 = cpu multithreading
    int[] threadsPerType = {1 ,1}; // 0 = data multithreading, 1 = cpu multithreading
    String[] multiThreadingTypes = {"-nt", "-nct"};
    DecimalFormat onedec;
    Reducer.Context context;
    
    
    public GATKTools(String reference, String bin, int threadingType) {
        this.reference = reference;
        this.bin = bin;
        this.java = "java";
        this.gatk = bin + "/GenomeAnalysisTK.jar" ;
        this.threadingType = threadingType % 2;
        onedec = new DecimalFormat("###0.0");
    }
    
    public void setThreadsPerType(int dataThreads, int cpuThreads) {
        threadsPerType[0] = dataThreads;
        threadsPerType[1] = cpuThreads;
    }
    
    public void setThreadingType(int threadingType) {
        this.threadingType = threadingType % 2;
    }

    public void setContext(Reducer.Context context) {
        this.context = context;
        mem = "-Xmx" + context.getConfiguration().get("mapreduce.reduce.memory.mb") + "m";
    }
    
    public void setMemory(int megs) {
        mem = "-Xmx" + megs + "m";
    }
        
    public GATKTools(String reference, String bin) {
        this.reference = reference;
        this.bin = bin;
        this.java = "java";
        this.gatk = bin + "/GenomeAnalysisTK.jar" ;
        onedec = new DecimalFormat("###0.0");
    }

    public String getJava() {
        return java;
    }

    public void setJava(String java) {
        this.java = java;
    }
    
    private static String[] AddCustomArguments(String[] command, String customArgs) {
        if(customArgs.isEmpty()) return command;
        ArrayList<String> tmp = new ArrayList(Arrays.asList(command));
        tmp = CommandGenerator.addToCommand(tmp, customArgs);  
        Object[] ObjectList = tmp.toArray();
        return Arrays.copyOf(ObjectList,ObjectList.length,String[].class);  
    }
    
    public void runBaseRecalibrator(String input, String table, String ref, String knownSite, String region) throws InterruptedException {
        String[] knownSites = {knownSite};
        runBaseRecalibrator(input, table, ref, knownSites, region);        
    }
            
    public void runBaseRecalibrator(String input, String table, String ref, String[] knownSites, String region) throws InterruptedException {        
        /**
         * example: from CountCovariates
         * -I input.bam -T Countcovariates -R ref -knownSites dbsnp
         * -cov ReadGroupCovariate -cov QualityScoreCovariate -cov DinucCovariate
         * -cov HomopolymerCovariate
         * -recalFile recal.csv
         * 
         * java -Xmx4g -jar GenomeAnalysisTK.jar \
            -T BaseRecalibrator \
            -I my_reads.bam \
            -R resources/Homo_sapiens_assembly18.fasta \
            -knownSites bundle/hg18/dbsnp_132.hg18.vcf \
            -knownSites another/optional/setOfSitesToMask.vcf \
            -o recal_data.table
         */

        ArrayList<String> command = new ArrayList<String>();
        String[] covString = {
            "-cov", "ReadGroupCovariate",
            "-cov", "QualityScoreCovariate",
            "-cov", "ContextCovariate"};
        String[] gatkcmd = {
            java, mem, "-jar", gatk,
            "-T", "BaseRecalibrator",
            multiThreadingTypes[1], "" + threadsPerType[1], // only -nct
            "-R", ref,
            "-I", input,
            "-o", table,
            "-L", region};
        command.addAll(Arrays.asList(gatkcmd));
        for(String knownSite : knownSites) {
            command.add("-knownSites");
            command.add(knownSite);
        }
//        command.addAll(Arrays.asList(covString));
        String customArgs = MyConf.getGatkBaseRecalibratorArgs(context.getConfiguration());
        command = CommandGenerator.addToCommand(command, customArgs);   
        Object[] objectList = command.toArray();
        long estimatedTime = runProcessAndWait("GATK BaseRecalibrator", Arrays.copyOf(objectList,objectList.length,String[].class));
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_RECAL).increment(estimatedTime);
    }

    public void runRealignerTargetCreator(String input, String targets, String ref, String region) throws InterruptedException {
        /**
         * example: 
         * java -Xmx2g -jar GenomeAnalysisTK.jar \
         * -T RealignerTargetCreator \
         * -R ref.fasta \
         * -I input.bam \
         * -o forIndelRealigner.intervals  
         * 
         */
        String[] command = {
            java, mem, "-jar", gatk,
            "-T", "RealignerTargetCreator",
            multiThreadingTypes[0], "" + threadsPerType[0], // only supports -nt
            "-R", ref,
            "-I", input,
            "-o", targets,
            "-L", region};
        String customArgs = MyConf.getGatkRealignerTargetCreatorArgs(context.getConfiguration());
        long estimatedTime = runProcessAndWait("GATK RealignerTargetCreator", AddCustomArguments(command, customArgs));    
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_TARGET_CREATOR).increment(estimatedTime);
    }

    public void runIndelRealigner(String input, String targets, String output, String ref, String region) throws InterruptedException {
        /**
         * example: 
         * java -Xmx4g -jar GenomeAnalysisTK.jar \
         * -T IndelRealigner \
         * -R ref.fasta \
         * -I input.bam \
         * -targetIntervals intervalListFromRTC.intervals \
         * -o realignedBam.bam \
         * [-known /path/to/indels.vcf] \
         * [-compress 0]    
         * 
         */
        String[] command = {
            java, mem, "-jar", gatk,
            "-T", "IndelRealigner",
            "-R", ref,
            "-I", input,
            "-targetIntervals", targets,
            "-o", output,
            "-L", region};
        String customArgs = MyConf.getGatkIndelRealignerArgs(context.getConfiguration());
        long estimatedTime = runProcessAndWait("GATK IndelRealigner", AddCustomArguments(command, customArgs));   
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_INDEL_REALN).increment(estimatedTime);    
    }

    public void runPrintReads(String input, String output, String ref, String table, String region) throws InterruptedException {
        /**
         * example:
         * -I input.bam -o recalibrated.bam -T TableRecalibration -recalFile recal.csv -R ref
         * Not using multi-threading, tests show best performance is single thread
         */
        String[] command = {
            java, mem, "-jar", gatk,
            "-T", "PrintReads",
            "-R", ref,
            "-I", input,
            "-o", output,
            "-BQSR", table,
            "-L", region};
        String customArgs = MyConf.getGatkPrintReadsArgs(context.getConfiguration());
        long estimatedTime = runProcessAndWait("GATK PrintReads", AddCustomArguments(command, customArgs));  
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_PRINT_READS).increment(estimatedTime);        
    }
    
    public String roundOneDecimal(double val) {
        return onedec.format(val);
    }
    
    public void runCombineVariants(String[] inputs, String output, String ref) throws InterruptedException {
        /**
         *  java -Xmx2g -jar GenomeAnalysisTK.jar \
         *  -R ref.fasta \
         *  -T CombineVariants \
         *  --variant input1.vcf \
         *  --variant input2.vcf \
         *  -o output.vcf \
         *  -genotypeMergeOptions UNIQUIFY
         */
        ArrayList<String> command = new ArrayList<String>();
        
        String[] gatkcmd = {
            java, mem, "-jar", gatk,
            "-T", "CombineVariants",
            multiThreadingTypes[0], "" + threadsPerType[0], // supports both nt and nct
            "-R", ref,
            "-o", output, "-sites_only",
            "-genotypeMergeOptions", "UNIQUIFY"};
        command.addAll(Arrays.asList(gatkcmd));
        if(inputs != null) {
            for(String input : inputs) {
                command.add("--variant");
                command.add(input);
            }
        }
        String customArgs = MyConf.getGatkCombineVariantsArgs(context.getConfiguration());
        command = CommandGenerator.addToCommand(command, customArgs);   
        Object[] objectList = command.toArray();
        long estimatedTime = runProcessAndWait("GATK CombineVariants", Arrays.copyOf(objectList,objectList.length,String[].class));
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_COMBINE_VCF).increment(estimatedTime);
    }

    public void runVariantCaller(String input, String output, boolean useUnifiedGenotyper, 
            double scc, double sec, String ref, String[] knownSites, String region) throws InterruptedException {
        /**
         * example:
         * -I recalibrated.bam -T UnifiedGenotyper -o output.vcf -R ref
         */
        ArrayList<String> command = new ArrayList<String>();
        String VC = variantCaller[0];
        String theadtype = multiThreadingTypes[1];
        int threadsToUse = threadsPerType[1];
        if(useUnifiedGenotyper) {
            VC = variantCaller[1];
            theadtype = multiThreadingTypes[threadingType];
            threadsToUse = threadsPerType[threadingType];
        }
        
        String[] gatkcmd = {
            java, mem, "-jar", gatk,
            "-T", VC,
            theadtype, "" + threadsToUse, // supports both nt and nct
            "-R", ref,
            "-I", input,
            "-o", output,
            "-stand_call_conf", roundOneDecimal(scc),
            "-stand_emit_conf", roundOneDecimal(sec),
            "-L", region,
            "--no_cmdline_in_header"};
        command.addAll(Arrays.asList(gatkcmd));
//        if(useUnifiedGenotyper && threadingType == 0 && (threadsPerType[1] /  threadsPerType[0]) >= 2) {
//            // add cpu threads per data threads
//            command.add(multiThreadingTypes[1]);
//            command.add("" + Math.max(1, (threadsPerType[1] /  threadsPerType[0])));
//        }
        if(knownSites != null) {
            for(String knownSite : knownSites) {
                command.add("-dbsnp");
                command.add(knownSite);
            }
        }
        String customArgs = MyConf.getGatkVariantCallerArgs(context.getConfiguration());
        command = CommandGenerator.addToCommand(command, customArgs);   
        Object[] objectList = command.toArray();
        long estimatedTime = runProcessAndWait("GATK " + VC, Arrays.copyOf(objectList,objectList.length,String[].class));   
        if(context != null)
            context.getCounter(HalvadeCounters.TIME_GATK_VARIANT_CALLER).increment(estimatedTime);
    }
    
    private long runProcessAndWait(String name, String[] command) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        ProcessBuilderWrapper builder = new ProcessBuilderWrapper(command, null);
        builder.startProcess(true);
        int error = builder.waitForCompletion();
        if(error != 0)
            throw new ProcessException(name, error);
        long estimatedTime = System.currentTimeMillis() - startTime;
        Logger.DEBUG("estimated time: " + estimatedTime / 1000);
        return estimatedTime;
    }
}
