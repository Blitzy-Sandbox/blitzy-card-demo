//TRANREPT JOB 'TRANSACTION REPORT',                                            
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
//* JOB: TRANREPT - Transaction Report Generation
//* Produces a formatted transaction report for a
//* specified date range. Three-stage pipeline:
//*   1. REPROC: Unload TRANSACT VSAM to sequential backup
//*   2. SORT: Filter by date range and sort by card number
//*   3. CBTRN03C: Generate formatted paginated report
//* Date filtering: Hardcoded date parameters in SORT step
//*   PARM-START-DATE = '2022-01-01'
//*   PARM-END-DATE = '2022-07-06'
//* Related program: app/cbl/CBTRN03C.cbl
//* Batch pipeline: ...COMBTRAN -> TRANREPT
//* NOTE: Two steps share name STEP05R (duplicate name)
//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')                                  
//* ********************************************************`***********        
//* Unload the processed transaction file                                       
//* *******************************************************************         
//* STEP05R (first): REPROC - Unload TRANSACT VSAM
//*   Uses REPROC procedure with CNTL library
//*   PRC001.FILEIN: TRANSACT VSAM KSDS source
//*   PRC001.FILEOUT: TRANSACT.BKUP(+1) GDG generation
//*     LRECL=350, RECFM=FB
//STEP05R EXEC PROC=REPROC,                                                     
// CNTLLIB=AWS.M2.CARDDEMO.CNTL                                                 
//*                                                                             
//PRC001.FILEIN  DD DISP=SHR,                                                   
//        DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS                                
//*                                                                             
//PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE),                                    
//        UNIT=SYSDA,                                                           
//        DCB=(LRECL=350,RECFM=FB,BLKSIZE=0),                                   
//        SPACE=(CYL,(1,1),RLSE),                                               
//        DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)                                 
//* *******************************************************************         
//* Filter the transactions for a the parm date and sort by card num            
//* *******************************************************************         
//* STEP05R (second, duplicate name): SORT - Date filter
//*   SYMNAMES: Define symbolic names for SORT control:
//*     TRAN-CARD-NUM (pos 263, 16 bytes, ZD) - card number
//*     TRAN-PROC-DT (pos 305, 10 bytes, CH) - process date
//*     PARM-START-DATE = '2022-01-01' (filter start)
//*     PARM-END-DATE = '2022-07-06' (filter end)
//*   SORT: Ascending by card number
//*   INCLUDE: Only records where process date falls within
//*     the PARM-START-DATE to PARM-END-DATE range
//*   SORTOUT: Date-filtered subset to TRANSACT.DALY(+1)
//STEP05R  EXEC PGM=SORT                                                        
//SORTIN   DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)                                
//SYMNAMES DD *                                                                 
TRAN-CARD-NUM,263,16,ZD                                                         
TRAN-PROC-DT,305,10,CH                                                          
PARM-START-DATE,C'2022-01-01'                                      //Date       
PARM-END-DATE,C'2022-07-06'                                        //Date       
//SYSIN    DD *                                                                 
 SORT FIELDS=(TRAN-CARD-NUM,A)                                                  
 INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,                             
         TRAN-PROC-DT,LE,PARM-END-DATE)                                         
/*                                                                              
//SYSOUT   DD SYSOUT=*                                                          
//SORTOUT  DD DISP=(NEW,CATLG,DELETE),                                          
//         UNIT=SYSDA,                                                          
//         DCB=(*.SORTIN),                                                      
//         SPACE=(CYL,(1,1),RLSE),                                              
//         DSN=AWS.M2.CARDDEMO.TRANSACT.DALY(+1)                                
//* *******************************************************************         
//* Produce a formatted report for processed transactions                       
//* *******************************************************************         
//* STEP10R: Execute CBTRN03C - report generation engine
//*   Reads date-filtered transactions and produces a
//*   formatted, paginated transaction report with 3-level
//*   totals (card, category, grand total).
//STEP10R EXEC PGM=CBTRN03C                                                     
//STEPLIB  DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.LOADLIB                                          
//SYSOUT   DD SYSOUT=*                                                          
//SYSPRINT DD SYSOUT=*                                                          
//* Input files:
//*   TRANFILE - Filtered transaction input from DALY(+1)
//*   CARDXREF - Card cross-reference (read, for lookups)
//*   TRANTYPE - Transaction type lookup (for descriptions)
//*   TRANCATG - Transaction category lookup
//*   DATEPARM - Date parameter file for report headers
//TRANFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANSACT.DALY(+1)                                
//CARDXREF DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS                               
//TRANTYPE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS                               
//TRANCATG DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS                               
//DATEPARM DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.DATEPARM                                         
//* Output files:
//*   TRANREPT - Report output GDG(+1), LRECL=133
//*              (ASA carriage control column + 132 chars)
//TRANREPT DD DISP=(NEW,CATLG,DELETE),                                          
//         UNIT=SYSDA,                                                          
//         DCB=(LRECL=133,RECFM=FB,BLKSIZE=0),                                  
//         SPACE=(CYL,(1,1),RLSE),                                              
//         DSN=AWS.M2.CARDDEMO.TRANREPT(+1)                                     
//                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
