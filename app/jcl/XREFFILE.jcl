//XREFFILE JOB 'Delete define cross ref file',CLASS=A,MSGCLASS=0,
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
//*
//* JOB: XREFFILE - Provision Card-Account Cross-Reference
//* Rebuilds the Card-Account Cross-Reference VSAM KSDS
//* file with alternate index (AIX) and PATH for account-
//* based cross-reference lookups.
//* Dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
//* KSDS key: 16 bytes at offset 0 (card number)
//* Record size: 50 bytes (fixed)
//* AIX: CARDXREF.VSAM.AIX - KEYS(11,25) on account ID
//*   at offset 25, NONUNIQUEKEY, FREESPACE(10,20)
//* PATH: CARDXREF.VSAM.AIX.PATH
//* Seed data: AWS.M2.CARDDEMO.CARDXREF.PS
//* Copybook layout: CVACT03Y.cpy
//* Consumed by: COACTVWC (account view via AIX path),
//*   CBTRN02C (POSTTRAN xref validation), CBACT03C
//*   (batch read), CREASTMT, TRANREPT
//*
//* *******************************************************************         
//* DELETE CARD XREF VSAM FILE IF ONE ALREADY EXISTS                            
//* *******************************************************************         
//* IDCAMS DELETE - Remove existing base cluster and AIX
//* if present. Two DELETE commands issued, each followed
//* by IF MAXCC LE 08 to reset the condition code so the
//* job continues cleanly when datasets do not yet exist.
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS -                                  
          CLUSTER                                                               
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
   DELETE  AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX  -                                 
          ALTERNATEINDEX                                                        
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE CARD XREF VSAM FILE                                                  
//* *******************************************************************         
//* IDCAMS DEFINE CLUSTER - Create the CARDXREF KSDS.
//* KEYS(16 0) = 16-byte card number primary key at
//*   offset 0. RECORDSIZE(50 50) = fixed 50-byte records
//*   matching the CVACT03Y.cpy layout.
//* SHAREOPTIONS(2 3) = cross-region read integrity with
//*   cross-system read/write sharing.
//* ERASE = clear data component on cluster deletion.
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(16 0) -                                                          
          RECORDSIZE(50 50) -                                                   
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* IDCAMS REPRO - Load seed cross-reference records from
//* the flat file AWS.M2.CARDDEMO.CARDXREF.PS into the
//* newly defined VSAM KSDS.
//* XREFDATA DD - Input: sequential flat file (PS) with
//*   50-byte fixed-length cross-reference records.
//* XREFVSAM DD - Output: target VSAM KSDS cluster.
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//XREFDATA DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.PS                                      
//XREFVSAM DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS                               
//SYSIN    DD   *                                                               
   REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)                                     
/*                                                                              
//*********************************************************************         
//* CREATE ALTERNATE INDEX ON ACCT ID                                           
//*********************************************************************         
//* IDCAMS DEFINE ALTERNATEINDEX on the base KSDS.
//* KEYS(11,25) = 11-byte account ID field located at
//*   byte offset 25 within each 50-byte record.
//* NONUNIQUEKEY - multiple card numbers can map to the
//*   same account ID (one-to-many relationship).
//* UPGRADE - AIX is automatically kept in sync whenever
//*   the base cluster is updated.
//* FREESPACE(10,20) - reserve 10% free space per CI and
//*   20% free space per CA for future inserts.
//STEP20  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX)-              
   RELATE(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)                    -              
   KEYS(11,25)                                                   -              
   NONUNIQUEKEY                                                  -              
   UPGRADE                                                       -              
   RECORDSIZE(50,50)                                             -              
   FREESPACE(10,20)                                              -              
   VOLUMES(AWSHJ1)                                               -              
   CYLINDERS(5,1))                                               -              
   DATA (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.DATA))           -              
   INDEX (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.INDEX))                        
/*                                                                              
//*********************************************************************         
//* DEFINE PATH IS USED TO RELATE THE ALTERNATE INDEX TO BASE CLUSTER           
//*********************************************************************         
//* IDCAMS DEFINE PATH - Links the AIX to the base
//* cluster, enabling browse of cross-reference records
//* by account ID. Programs such as COACTVWC use this
//* path for account-based cross-reference lookups.
//STEP25  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
  DEFINE PATH                                           -                       
   (NAME(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH)        -                       
    PATHENTRY(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX))                               
/*                                                                              
//*********************************************************************         
//* BUILD ALTERNATE INDEX CLUSTER                                               
//*********************************************************************         
//* IDCAMS BLDINDEX - Builds the AIX entries by scanning
//* the base KSDS data. Must run after DEFINE
//* ALTERNATEINDEX and DEFINE PATH to populate the
//* alternate index with account-ID-to-card mappings.
//STEP30  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   BLDINDEX                                                      -              
   INDATASET(AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS)                 -              
   OUTDATASET(AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX)                                
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:09 CDT
//*
