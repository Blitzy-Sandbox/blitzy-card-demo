//CNJBATMP JOB 'Compile Batch COBOL Program',CLASS=A,MSGCLASS=H,        
//             MSGLEVEL=(1,1),REGION=0M,NOTIFY=&SYSUID,TIME=1440
//*  JOB Card Parameters:
//*  CLASS=A        - Standard batch execution class for JES
//*  MSGCLASS=H     - Hold job output in SDSF held queue
//*  MSGLEVEL=(1,1) - Print all JCL stmts and alloc msgs
//*  REGION=0M      - Request max available memory (no cap)
//*  NOTIFY=&SYSUID - Notify submitting TSO user on finish
//*  TIME=1440      - Max CPU time (1440 min = 24h, no limit)
//*********************************************************************         
//*  change BATCHPGM to your program name everywhere                            
//*----->   C BATCHPGM xyz all <--------                                        
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
//****  Sample Batch COBOL Compile JCL                           ******         
//****  Check with your Administrator for                        ******         
//****  JCL suitable to your environment                         ******
//*********************************************************************    
//*  change BATCHPGM to your program name everywhere                            
//*----->   C BATCHPGM xyz all <--------                                        
//*********************************************************************         
//****  COMPILE BATCH COBOL PROGRAM                              ******         
//*********************************************************************         
//*  Set Parms for this compile:                                                
//*********************************************************************         
//*  MEMNAME defines the batch COBOL program member name
//*  to compile. BATCHPGM is a placeholder - replace with
//*  the actual member name from the source PDS.
//*  The BUILDBAT procedure references this via MEM= parm.
//   SET MEMNAME=BATCHPGM                                                       
//*  HLQ defines the high-level qualifier for all CardDemo
//*  dataset names. Default AWS.M2 corresponds to the AWS
//*  Mainframe Modernization demo environment. Change to
//*  match site-specific dataset naming conventions.
//*  Resolves procedure library and all dataset refs in
//*  the BUILDBAT procedure.
//   SET HLQ=AWS.M2                                                             
//*********************************************************************         
//*  Add proclib reference                                                      
//*********************************************************************         
//*  JCLLIB ORDER tells JES where to find the BUILDBAT
//*  cataloged procedure. The procedure library resolves
//*  to &HLQ..CARDDEMO.PRC.UTIL (e.g.,
//*  AWS.M2.CARDDEMO.PRC.UTIL). BUILDBAT contains the
//*  actual compile and link-edit JCL steps.
//CCLIBS  JCLLIB ORDER=&HLQ..CARDDEMO.PRC.UTIL                                  
//*********************************************************************         
//*  compile the COBOL code:                                                    
//*********************************************************************         
//*  Invokes the BUILDBAT cataloged procedure to compile
//*  and link-edit the batch COBOL program.
//*  MEM=&MEMNAME passes the program member name for
//*  source retrieval and load module naming.
//*  HLQ=&HLQ passes the high-level qualifier for all
//*  dataset resolution within the procedure.
//*  Batch programs need no NEWCOPY step as they run
//*  via JCL job submission, not in a CICS region.
//BATCMP       EXEC BUILDBAT,MEM=&MEMNAME,HLQ=&HLQ                              
