//ACCTFILE JOB 'Delete define Account Data',CLASS=A,MSGCLASS=0,                 
// NOTIFY=&SYSUID                         
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
//* JOB: ACCTFILE - Provision Account Data VSAM File
//* Deletes, defines, and loads the Account Data VSAM
//* KSDS file -- the primary account master dataset for
//* the CardDemo application.
//* Dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
//* KSDS key: 11 bytes at offset 0 (account ID)
//* Record size: 300 bytes (fixed)
//* Seed data: AWS.M2.CARDDEMO.ACCTDATA.PS
//* Copybook layout: CVACT01Y.cpy
//* Consumed by: COACTVWC, COACTUPC (online account
//*   view/update), CBACT01C (batch read), CBACT04C
//*   (interest calc), POSTTRAN/CBTRN02C, CREASTMT
//* *******************************************************************         
//* DELETE ACCOUNT VSAM FILE IF ONE ALREADY EXISTS                              
//* *******************************************************************         
//* STEP05: IDCAMS DELETE - Remove existing ACCTDATA
//*   cluster. IF MAXCC LE 08: Ignores 'not found'
//*   error (RC=8) to make job safely rerunnable.
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS -                                  
          CLUSTER                                                               
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE ACCOUNT VSAM FILE                                                    
//* *******************************************************************         
//* STEP10: IDCAMS DEFINE CLUSTER - Create ACCTDATA KSDS
//*   KEYS(11 0) = 11-byte account ID key at offset 0
//*   RECORDSIZE(300 300) = fixed 300-byte records
//*   CYLINDERS(1 5) = primary 1 cyl, secondary 5 cyl
//*   SHAREOPTIONS(2 3) = cross-region read share,
//*     cross-system read-only
//*   ERASE = securely clears data on cluster deletion
//*   INDEXED = KSDS (Key-Sequenced Data Set)
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(11 0) -                                                          
          RECORDSIZE(300 300) -                                                 
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* STEP15: IDCAMS REPRO - Load account seed data
//*   Copies records from flat sequential file to VSAM
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//*   ACCTDATA DD: Source flat file (ACCTDATA.PS)
//ACCTDATA DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.ACCTDATA.PS                                      
//*   ACCTVSAM DD: Target VSAM KSDS cluster
//ACCTVSAM DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(ACCTDATA) OUTFILE(ACCTVSAM)                                     
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:04 CDT
//*
