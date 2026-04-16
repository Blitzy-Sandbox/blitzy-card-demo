//TRANTYPE JOB 'DEFINE TRAN TYPE',                                              
// CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID           
//******************************************************************
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
//******************************************************************            
//* JOB: TRANTYPE - Provision Transaction Type Lookup File
//* Deletes, defines, and loads the Transaction Type
//* lookup VSAM KSDS file. Contains 2-byte type codes
//* and descriptions for transaction classification.
//* Dataset: AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS
//* KSDS key: 2 bytes at offset 0 (type code)
//* Record size: 60 bytes (fixed)
//* Seed data: AWS.M2.CARDDEMO.TRANTYPE.PS
//* Copybook layout: CVTRA03Y.cpy
//* Consumed by: TRANREPT (CBTRN03C)
//* NOTE: Uses SHAREOPTIONS(1 4) unlike most other files
//*   which use (2 3). This means exclusive control for
//*   cross-region access and read-only cross-system.
//*
//* *******************************************************************         
//* STEP05: IDCAMS DELETE - Remove existing TRANTYPE cluster
//* DELETE TRANSACATION TYPE VSAM FILE IF ONE ALREADY EXISTS                    
//* SET MAXCC=0 ensures the job continues cleanly even if the
//* dataset does not yet exist, making the step idempotent.
//* *******************************************************************         
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS -                                  
          CLUSTER                                                               
   SET    MAXCC = 0                                                             
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* STEP10: IDCAMS DEFINE CLUSTER - Create TRANTYPE VSAM KSDS
//* DEFINE TRANSACATION TYPE VSAM FILE                                          
//* KEYS(2 0) = 2-byte transaction type code at offset 0
//* RECORDSIZE(60 60) = fixed 60-byte records
//* SHAREOPTIONS(1 4) = exclusive cross-region, read-only
//*   cross-system (differs from (2 3) used by most files)
//* ERASE = overwrite data component on deletion for security
//* *******************************************************************         
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(2 0) -                                                           
          RECORDSIZE(60 60) -                                                   
          SHAREOPTIONS(1 4) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* STEP15: IDCAMS REPRO - Load seed data into VSAM KSDS
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* Copies records from the sequential flat file (TRANTYPE.PS)
//* into the newly defined VSAM KSDS cluster.
//* *******************************************************************         
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//* TRANTYPE DD - Input: flat sequential file with type records
//TRANTYPE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANTYPE.PS                                      
//* TTYPVSAM DD - Output: target VSAM KSDS (DISP=OLD for load)
//TTYPVSAM DD DISP=OLD,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(TRANTYPE) OUTFILE(TTYPVSAM)                                     
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
