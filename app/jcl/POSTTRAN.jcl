//POSTTRAN JOB 'POSTTRAN',CLASS=A,MSGCLASS=0,                                   
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
//* JOB: POSTTRAN - Daily Transaction Posting
//* Executes COBOL program CBTRN02C to process the daily
//* transaction file. This is the first step in the batch
//* business processing pipeline.
//* Processing flow:
//*   1. Reads daily transactions from DALYTRAN.PS
//*   2. Validates each against cross-reference (XREFFILE)
//*   3. Looks up account data for balance checks
//*   4. Updates transaction master (TRANSACT VSAM)
//*   5. Updates category balances (TCATBALF VSAM)
//*   6. Writes rejected transactions to DALYREJS(+1) GDG
//* Related program: app/cbl/CBTRN02C.cbl
//* Batch pipeline: POSTTRAN -> INTCALC -> TRANBKP ->
//*   COMBTRAN -> CREASTMT/TRANREPT
//* *******************************************************************         
//* Process and load daily transaction file and create transaction              
//* category balance and update transaction master vsam                         
//* *******************************************************************         
//* STEP15: Execute CBTRN02C - transaction posting engine
//*   4-stage validation: xref lookup, account lookup,
//*   balance check, category update
//STEP15 EXEC PGM=CBTRN02C                                                      
//*   STEPLIB  - CardDemo load module library
//*   TRANFILE - Transaction master VSAM KSDS (read/update)
//*              350-byte records, key=16 (transaction ID)
//*   DALYTRAN - Daily transaction input file (sequential)
//*              Source: AWS.M2.CARDDEMO.DALYTRAN.PS
//*   XREFFILE - Card-account cross-reference (read-only)
//*              Used for card-to-account validation
//*   DALYREJS - Rejected transaction output GDG(+1)
//*              RECFM=F, LRECL=430 (350 + reject reason)
//*              New generation created each run
//*   ACCTFILE - Account master VSAM KSDS (read-only)
//*              Used for balance validation
//*   TCATBALF - Category balance VSAM KSDS (read/update)
//*              Updated with new category totals
//STEPLIB  DD DISP=SHR,                                                         
//            DSN=AWS.M2.CARDDEMO.LOADLIB                                       
//SYSPRINT DD SYSOUT=*                                                          
//SYSOUT   DD SYSOUT=*                                                          
//TRANFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS                               
//DALYTRAN DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.DALYTRAN.PS                                      
//XREFFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS                               
//DALYREJS DD DISP=(NEW,CATLG,DELETE),                                          
//         UNIT=SYSDA,                                                          
//         DCB=(RECFM=F,LRECL=430,BLKSIZE=0),                                   
//         SPACE=(CYL,(1,1),RLSE),                                              
//         DSN=AWS.M2.CARDDEMO.DALYREJS(+1)                                     
//ACCTFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS                               
//TCATBALF DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS                               
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
