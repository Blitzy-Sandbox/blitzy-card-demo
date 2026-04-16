//TRANCATG JOB 'DEFINE TRAN CATEGORY',                                          
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
//*
//* JOB: TRANCATG - Provision Transaction Category Type File
//* Deletes, defines, and loads the Transaction Category
//* lookup VSAM KSDS file. Contains category codes and
//* descriptions used for transaction classification.
//* Dataset: AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
//* KSDS key: 6 bytes at offset 0 (category code)
//* Record size: 60 bytes (fixed)
//* Seed data: AWS.M2.CARDDEMO.TRANCATG.PS
//* Copybook layout: CVTRA04Y.cpy
//* Consumed by: TRANREPT (CBTRN03C)
//*
//* *******************************************************************         
//* DELETE TRANSACTION CATEGORY TYPE VSAM FILE IF ONE ALREADY EXISTS            
//* *******************************************************************         
//* IDCAMS DELETE - Remove existing TRANCATG cluster.
//* SET MAXCC=0 ensures idempotent reruns (ignores
//* 'cluster not found' condition).
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS -                                  
          CLUSTER                                                               
   SET    MAXCC = 0                                                             
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE TRANSACTION CATEGORY TYPE VSAM FILE                                  
//* *******************************************************************         
//* IDCAMS DEFINE CLUSTER - Create TRANCATG VSAM KSDS.
//* KEYS(6 0) = 6-byte category key at offset 0.
//* RECORDSIZE(60 60) = fixed 60-byte records.
//* SHAREOPTIONS(2 3) = cross-region read sharing.
//* ERASE = overwrite data component on cluster deletion.
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(6 0) -                                                           
          RECORDSIZE(60 60) -                                                   
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* IDCAMS REPRO - Load seed data from flat file
//* TRANCATG.PS into the VSAM KSDS cluster.
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//* TRANCATG DD - Input flat file containing seed data
//*   (DISP=SHR for shared read access)
//TRANCATG DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANCATG.PS                                      
//* TCATVSAM DD - Output VSAM target (DISP=OLD for exclusive
//*   write access during REPRO load)
//TCATVSAM DD DISP=OLD,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(TRANCATG) OUTFILE(TCATVSAM)                                     
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
