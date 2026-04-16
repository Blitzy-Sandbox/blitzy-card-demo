//TRANFILE JOB 'DEFINE TRANSACTION MASTER',CLASS=A,MSGCLASS=0,                  
//  NOTIFY=&SYSUID     
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
//* JOB: TRANFILE - Provision Transaction Master VSAM File
//* Full rebuild of the Transaction Master VSAM KSDS file
//* with AIX on processed timestamp and CICS close/reopen.
//* Dataset: AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
//* KSDS key: 16 bytes at offset 0 (transaction ID)
//* Record size: 350 bytes (fixed)
//* AIX: TRANSACT.VSAM.AIX - KEYS(26 304) on processed
//*   timestamp at offset 304, NONUNIQUEKEY
//* PATH: TRANSACT.VSAM.AIX.PATH
//* Seed data: AWS.M2.CARDDEMO.DALYTRAN.PS.INIT
//*   (initial load file, not the daily transaction feed)
//* Copybook layout: CVTRA05Y.cpy (master record)
//* Consumed by: COTRN00C-02C (online transaction screens),
//*   POSTTRAN (CBTRN02C), TRANBKP, COMBTRAN, TRANREPT,
//*   CREASTMT, READCARD utility
//*
//*********************************************************************         
//* Close files in CICS region                                                  
//*********************************************************************         
//* SDSF - Close TRANSACT and CXACAIX files in CICS region
//*   CICSAWSA before rebuild. Files must be closed to allow
//*   IDCAMS DELETE/DEFINE operations on the VSAM clusters.
//CLCIFIL EXEC PGM=SDSF                                                         
//ISFOUT DD SYSOUT=*                                                            
//CMDOUT DD SYSOUT=*                                                            
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(TRANSACT ) CLO'                                      
 /F CICSAWSA,'CEMT SET FIL(CXACAIX ) CLO'                                       
/*                                                                              
//* *******************************************************************         
//* DELETE TRANSACATION MASTER VSAM FILE IF ONE ALREADY EXISTS                  
//* *******************************************************************         
//* IDCAMS DELETE - Remove existing base cluster and AIX.
//*   IF MAXCC LE 08 suppresses 'not found' (RC=8) so job
//*   continues cleanly on first-time provisioning.
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS -                                  
          CLUSTER                                                               
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
   DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX -                                   
          ALTERNATEINDEX                                                        
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE TRANSACATION MASTER VSAM FILE                                        
//* *******************************************************************         
//* IDCAMS DEFINE CLUSTER - KEYS(16 0) = 16-byte transaction
//*   ID at offset 0. RECORDSIZE(350 350) = fixed 350-byte
//*   records. SHAREOPTIONS(2 3) for cross-region read sharing.
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(16 0) -                                                          
          RECORDSIZE(350 350) -                                                 
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* IDCAMS REPRO - Load initial seed data from the flat file
//*   DALYTRAN.PS.INIT into the newly defined VSAM KSDS.
//*   NOTE: Source is DALYTRAN.PS.INIT (initial load file),
//*   NOT the daily transaction feed DALYTRAN.PS.
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//* TRANSACT DD: Source initial data file (DALYTRAN.PS.INIT -
//*   not same as the daily transaction feed DALYTRAN.PS)
//TRANSACT DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.DALYTRAN.PS.INIT                                 
//* TRANVSAM DD: Target VSAM KSDS cluster
//TRANVSAM DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)                                     
/*                                                                              
//*-------------------------------------------------------------------*         
//* CREATE ALTERNATE INDEX ON PROCESSED TIMESTAMP                               
//*-------------------------------------------------------------------*         
//* IDCAMS DEFINE ALTERNATEINDEX - KEYS(26 304) defines a
//*   26-byte processed timestamp key at byte offset 304.
//*   NONUNIQUEKEY allows duplicate timestamps across
//*   transactions. UPGRADE keeps AIX in sync with base.
//STEP20  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)-              
   RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)                    -              
   KEYS(26 304)                                                  -              
   NONUNIQUEKEY                                                  -              
   UPGRADE                                                       -              
   RECORDSIZE(350,350)                                           -              
   VOLUMES(AWSHJ1)                                               -              
   CYLINDERS(5,1))                                               -              
   DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.DATA))           -              
   INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.INDEX))                        
/*                                                                              
//*-------------------------------------------------------------------*         
//* DEFINE PATH IS USED TO RELATE THE ALTERNATE INDEX TO BASE CLUSTER           
//*-------------------------------------------------------------------*         
//* IDCAMS DEFINE PATH - Create access path for
//*   timestamp-based AIX browsing. PATH relates the AIX
//*   to the base cluster for transparent alternate key access.
//STEP25  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
  DEFINE PATH                                           -                       
   (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH)        -                       
    PATHENTRY(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX))                               
/*                                                                              
//*------------------------------------------------------------------           
//* BUILD ALTERNATE INDEX CLUSTER                                               
//*-------------------------------------------------------------------*         
//* IDCAMS BLDINDEX - Build AIX entries from base cluster
//*   data. Populates the alternate index with entries
//*   derived from the records in the base KSDS.
//STEP30  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   BLDINDEX                                                      -              
   INDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)                 -              
   OUTDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)                                
/*                                                                              
//*********************************************************************         
//* Opem files in CICS region                                                   
//*********************************************************************         
//* SDSF - Reopen TRANSACT and CXACAIX files in CICS region
//*   CICSAWSA after rebuild is complete.
//*   NOTE: Existing comment says "Opem" (typo for "Open") -
//*   documented as-is per original source.
//OPCIFIL EXEC PGM=SDSF                                                         
//ISFOUT DD SYSOUT=*                                                            
//CMDOUT DD SYSOUT=*                                                            
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(TRANSACT ) OPE'                                      
 /F CICSAWSA,'CEMT SET FIL(CXACAIX ) OPE'                                       
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
