//INTCALC JOB 'INTEREST CALCULATOR',CLASS=A,MSGCLASS=0,
//   NOTIFY=&SYSUID           
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
//* JOB: INTCALC - Interest Calculation and Fee Processing
//* Executes COBOL program CBACT04C to compute interest
//* charges and fees on account balances, generating new
//* system transactions for each calculated amount.
//* Processing flow:
//*   1. Reads category balances from TCATBALF
//*   2. Looks up cross-reference for account mapping
//*   3. Reads account data for rate determination
//*   4. Looks up disclosure group for interest rates
//*   5. Calculates interest/fees per category
//*   6. Writes generated transactions to SYSTRAN(+1) GDG
//* PARM='2022071800' = processing date (YYYYMMDD + seq)
//* Related program: app/cbl/CBACT04C.cbl
//* Batch pipeline: POSTTRAN -> INTCALC -> TRANBKP -> ...
//* *******************************************************************         
//* Process transaction balance file and compute interest and fees.
//* *******************************************************************         
//* STEP15: Execute CBACT04C - interest calculation engine
//*   PARM='2022071800': Processing date 2022-07-18,
//*     sequence 00. Date drives which balances to process.
//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'                                    
//*
//*   DD Allocations:
//*   STEPLIB  - CardDemo load module library
//*   TCATBALF - Category balance VSAM KSDS (input)
//*              Source of balances to apply interest to
//*   XREFFILE - Card cross-reference VSAM KSDS (read-only)
//*   XREFFIL1 - Cross-reference via AIX PATH (read-only)
//*              Allows account-based xref lookups
//*   ACCTFILE - Account master VSAM KSDS (read-only)
//*              Account details for rate determination
//*   DISCGRP  - Disclosure group VSAM KSDS (read-only)
//*              Interest rate parameters by disclosure grp
//*   TRANSACT - System-generated transaction output
//*              Written to SYSTRAN(+1) GDG generation
//*              RECFM=F, LRECL=350, new 350-byte records
//STEPLIB  DD DISP=SHR,                                                         
//            DSN=AWS.M2.CARDDEMO.LOADLIB                                       
//SYSPRINT DD SYSOUT=*                                                          
//SYSOUT   DD SYSOUT=*       
//TCATBALF DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS      
//XREFFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS    
//XREFFIL1 DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH    
//ACCTFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS                               
//DISCGRP  DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS                                
//TRANSACT DD DISP=(NEW,CATLG,DELETE),                                          
//         UNIT=SYSDA,                                                          
//         DCB=(RECFM=F,LRECL=350,BLKSIZE=0),                                   
//         SPACE=(CYL,(1,1),RLSE),                                              
//         DSN=AWS.M2.CARDDEMO.SYSTRAN(+1)           
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
