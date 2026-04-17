//CBLDBMS  JOB 'Compile BMS Map',CLASS=A,MSGCLASS=H,        
//             MSGLEVEL=(1,1),REGION=0M,NOTIFY=&SYSUID,TIME=1440
//* -----------------------------------------------------------
//*  JOB Card Parameter Descriptions:
//*  CLASS=A     - Standard batch execution class for JES
//*  MSGCLASS=H  - Hold output for SDSF held output queue
//*  MSGLEVEL=(1,1) - Print all JCL and allocation msgs
//*  REGION=0M   - Maximum region memory (no ceiling)
//*  NOTIFY=&SYSUID - Notify submitting TSO user on end
//*  TIME=1440   - Max CPU time (effectively unlimited)
//* -----------------------------------------------------------
//*********************************************************************
//*  Change CICSMAP to your map name everywhere
//*----->   C CICSMAP xyz all <--------
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
//****  Sample Assembler BMS Compile JCL                         ******         
//****  Check with your Administrator for                        ******         
//****  JCL suitable to your environment                         ******
//*********************************************************************
//****  Compile CICS BMS to generate Copybook                    ******
//*********************************************************************
//*  ---------------------------
//*  Set Parms for this compile:
//*  ---------------------------
//   SET HLQ=AWS.M2
//*  The SET symbol defines the high-level qualifier
//*  (HLQ) for all CardDemo dataset names in this job.
//*  Default AWS.M2 is the AWS Mainframe Modernization
//*  demo environment. Change to match site-specific
//*  dataset naming convention. Used for procedure lib
//*  resolution and all dataset refs in BUILDBMS.
//*
//*********************************************************************
//*  Add Proclib Reference
//*********************************************************************
//*  Resolves the BUILDBMS cataloged procedure from
//*  the CardDemo procedure library at
//*  &HLQ..CARDDEMO.PRC.UTIL
//*  (e.g., AWS.M2.CARDDEMO.PRC.UTIL).
//*  BUILDBMS contains the BMS assembly and link-edit
//*  steps. See samples/proc/BUILDBMS.prc for details.
//CCLIBS  JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL
//*  Invokes BUILDBMS to assemble and link-edit BMS
//*  map source into a physical mapset (load module)
//*  and generate a symbolic map copybook for COBOL
//*  COPY inclusion (see app/cpy-bms/).
//*  MAPNAME=CICSMAP - BMS map member name placeholder;
//*    replace with actual map name (e.g., COSGN00)
//*  HLQ=&HLQ - High-level qualifier for datasets
//STEP1 EXEC BUILDBMS,MAPNAME=CICSMAP,HLQ=&HLQ
//*********************************************************************
//****  CICS commands in batch to Execute NEWCOPY                ******
//*********************************************************************
//*  Executes IBM SDSF in batch mode to submit z/OS
//*  console commands from within this JCL job.
//*  NOTE: No COND= parameter on this step - NEWCOPY
//*  is always attempted regardless of the compile
//*  step return code. This differs from CICCMP.jcl
//*  which uses COND=(4,LT) to skip NEWCOPY on
//*  compile failure.
//SDSF1 EXEC PGM=SDSF
//*  ISFOUT - Captures SDSF status output to JES spool
//ISFOUT DD SYSOUT=*
//*  CMDOUT - Captures command output to JES spool
//CMDOUT DD SYSOUT=*
//*  ISFIN - Inline data input for SDSF commands.
//*  Commands between DD * and /* are passed to SDSF.
//ISFIN  DD *
//*  Issues z/OS MODIFY console command to CICS region
//*  CICSAWSA. CEMT SET PROG NEWCOPY tells CICS to
//*  discard the in-memory copy of the BMS map and
//*  load the newly compiled version from the load lib.
//*  CICSAWSA - Sample CICS region name; change to
//*    match your target CICS region.
//*  CICSMAP  - Must match MAPNAME= value from STEP1.
 /MODIFY CICSAWSA,'CEMT SET PROG(CICSMAP) NEWCOPY'
/*
