//CUSTFILE JOB 'DEFINE CUSTOMER FILE',CLASS=A,MSGCLASS=0,                       
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
//* JOB: CUSTFILE - Provision Customer Data VSAM File
//* Rebuilds the Customer Data VSAM KSDS file with CICS
//* file close/reopen cycle since CUSTDAT is an active
//* CICS resource.
//* Dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
//* KSDS key: 9 bytes at offset 0 (customer ID)
//* Record size: 500 bytes (fixed, largest in CardDemo)
//* Seed data: AWS.M2.CARDDEMO.CUSTDATA.PS
//* Copybook layout: CVCUS01Y.cpy
//* Consumed by: COACTVWC (account view joins customer),
//*   CBCUS01C (batch read), CREASTMT (CBSTM03A)
//*********************************************************************         
//* Close files in CICS region                                                  
//*********************************************************************         
//* CLCIFIL: SDSF - Close CUSTDAT in CICS region CICSAWSA
//CLCIFIL EXEC PGM=SDSF                                                         
//ISFOUT DD SYSOUT=*                                                            
//CMDOUT DD SYSOUT=*                                                            
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(CUSTDAT ) CLO'                                       
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DELETE CUSTOMER VSAM FILE IF ONE ALREADY EXISTS                             
//* *******************************************************************         
//* STEP05: IDCAMS DELETE - Remove existing CUSTDATA cluster
//*   IF MAXCC LE 08 for safe rerun
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS -                                  
          CLUSTER                                                               
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE CUSTOMER VSAM FILE                                                   
//* *******************************************************************         
//* STEP10: IDCAMS DEFINE CLUSTER - Create CUSTDATA KSDS
//*   KEYS(9 0) = 9-byte customer ID key at offset 0
//*   RECORDSIZE(500 500) = fixed 500-byte records
//*   (largest record in the CardDemo application)
//*   SHAREOPTIONS(2 3), ERASE, INDEXED
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(9 0) -                                                           
          RECORDSIZE(500 500) -                                                 
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* STEP15: IDCAMS REPRO - Load customer seed data
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//*   CUSTDATA DD: Source flat file (CUSTDATA.PS)
//CUSTDATA DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CUSTDATA.PS                                      
//*   CUSTVSAM DD: Target VSAM KSDS
//CUSTVSAM DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(CUSTDATA) OUTFILE(CUSTVSAM)                                     
/*                                                                              
//*********************************************************************         
//* Open files in CICS region                                                   
//*********************************************************************         
//* OPCIFIL: SDSF - Reopen CUSTDAT in CICS region
//OPCIFIL EXEC PGM=SDSF                                                         
//ISFOUT DD SYSOUT=*                                                            
//CMDOUT DD SYSOUT=*                                                            
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(CUSTDAT ) OPE'                                       
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:05 CDT
//*
