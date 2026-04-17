//CICCMP  JOB 'Compile CICS Program',CLASS=A,MSGCLASS=H,        
//             MSGLEVEL=(1,1),REGION=0M,NOTIFY=&SYSUID,TIME=1440
//*-------------------------------------------------------------------
//*  JOB Card Parameters:
//*  CLASS=A       - Standard batch execution class for JES
//*  MSGCLASS=H    - Hold output in SDSF held output queue
//*  MSGLEVEL=(1,1) - Print all JCL and allocation msgs
//*  REGION=0M     - Max available region memory (no limit)
//*  NOTIFY=&SYSUID - Notify submitting TSO user on end
//*  TIME=1440     - Max CPU time (effectively unlimited)
//*-------------------------------------------------------------------
//********************************************************************* 
//*  change CICSPGMN to your program name everywhere                            
//*----->   C CICSPGMN xyz all <--------                                        
//*  set    HLQ      to your high level qualifier                              
//*********************************************************************
//* Copyright Amazon.com, Inc. or its affiliates.                   
//* All Rights Reserved.                                            
//*                                                                 
//* Licensed under the Apache License, Version 2.0 (the "License"). 
//* You may not use this file except in compliance with the License.
//* You may obtain a copy of the License at                         
//*                                                                 
//*    http://www.apache.org/licenses/LICENSE-2.0                   
//*                                                                 
//* Unless required by applicable law or agreed to in writing,      
//* software distributed under the License is distributed on an     
//* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    
//* either express or implied. See the License for the specific     
//* language governing permissions and limitations under the License
//*********************************************************************    
//****  Sample CICS COBOL Compile JCL                            ******         
//****  Check with your Administrator for                        ******         
//****  JCL suitable to your environment                         ******
//*********************************************************************         
//****  Compile CICS COBOL program                               ******         
//****  After compiling the related maps                         ****** 
//*********************************************************************         
//*  Set Parms for this compile:                                                
//*********************************************************************         
//*  HLQ: High-level qualifier for all CardDemo datasets.
//*  Default AWS.M2 targets the AWS Mainframe Modernization
//*  demo environment. Change to match your site naming.
//   SET HLQ=AWS.M2                                                             
//*  MEMNAME: CICS COBOL program member name to compile.
//*  CICSPGMN is a placeholder - replace with the actual
//*  program name. Referenced by BUILDONL procedure via
//*  MEM= and by the NEWCOPY step for CICS refresh.
//*  IMPORTANT: Related BMS maps must already be compiled
//*  before compiling the CICS program (see note above).
//   SET MEMNAME=CICSPGMN                                                       
//*********************************************************************         
//*  Add proclib reference                                                      
//*********************************************************************         
//*  Resolves the BUILDONL cataloged procedure from the
//*  CardDemo procedure library at &HLQ..CARDDEMO.PRC.UTIL
//*  (e.g., AWS.M2.CARDDEMO.PRC.UTIL). The BUILDONL proc
//*  contains CICS translator, COBOL compile, and link-edit.
//CCLIBS  JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL                                  
//*********************************************************************         
//*  compile the COBOL code:                                                    
//*********************************************************************         
//*  Invokes BUILDONL cataloged procedure to compile and
//*  link-edit a CICS COBOL program.
//*  MEM=&MEMNAME  - Program member for source and load mod
//*  HLQ=&HLQ     - High-level qualifier for dataset paths
//*  BUILDONL includes CICS translator pre-processing that
//*  converts EXEC CICS statements into standard COBOL
//*  CALL statements before compilation.
//*  Step name CICSCMP is referenced by the COND parameter
//*  in the subsequent NEWCOPY step.
//CICSCMP      EXEC BUILDONL,MEM=&MEMNAME,HLQ=&HLQ                              
//*********************************************************************         
//****  CICS commands in batch to perform NEWCOPY                ******         
//*********************************************************************         
//*  NEWCOPY step: Refreshes the CICS in-memory program
//*  copy after a successful compile. Uses the SDSF batch
//*  interface to issue a z/OS MODIFY console command to
//*  the target CICS region.
//*
//*  COND=(4,LT) - Conditional execution safety check.
//*  Skips this step if any prior step return code > 4.
//*  Executes ONLY when all prior steps have RC <= 4
//*  (success or warning). If CICSCMP fails with RC > 4,
//*  NEWCOPY is bypassed to avoid refreshing CICS with
//*  a failed or incomplete program load module.
//*
//*  KEY DIFFERENCE from BMSCMP.jcl: The BMS compile
//*  wrapper has NO COND parameter on its SDSF step,
//*  so it always attempts NEWCOPY regardless of the
//*  compile return code. This CICS compile wrapper
//*  adds the COND safety check as a guard against
//*  deploying a failed compilation to the CICS region.
//NEWCOPY EXEC PGM=SDSF,COND=(4,LT)                                             
//*  ISFOUT - Captures SDSF status output (diagnostics)
//*  CMDOUT - Captures SDSF command execution output
//*  Both sent to SYSOUT=* for JES spool review.
//ISFOUT DD SYSOUT=*                                                            
//CMDOUT DD SYSOUT=*                                                            
//ISFIN  DD *                                                                   
//*  Issues z/OS MODIFY command to CICS region CICSAWSA.
//*  CEMT SET PROG(CICSPGMN) NEWCOPY tells CICS to
//*  discard the current in-memory copy and load the
//*  newly compiled version from the DFHRPL library.
//*  CICSAWSA - Sample CICS region (change to match
//*             your target CICS region name).
//*  CICSPGMN - Placeholder program name (should match
//*             the MEMNAME SET symbol value above).
 /MODIFY CICSAWSA,'CEMT SET PROG(CICSPGMN) NEWCOPY'                             
/*                                                                              
