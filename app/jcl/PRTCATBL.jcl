//PRTCATBL JOB 'Print Trasaction Category Balance File',                        
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
//* JOB: PRTCATBL - Print Transaction Category Balance File
//* Unloads the TCATBALF VSAM KSDS file to a sequential
//* backup, then sorts and formats it into a printable
//* report showing account balances by category.
//* Uses: REPROC cataloged procedure for VSAM unload,
//*   SORT utility for reformat and report generation
//* Input: AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS
//* Output: AWS.M2.CARDDEMO.TCATBALF.REPT (formatted)
//* Intermediate: AWS.M2.CARDDEMO.TCATBALF.BKUP(+1) (GDG)
//* NOTE: Existing comment on STEP10R section divider
//*   contains garbled text ("TCATBALFions") - as-is.
//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')                                  
//*
//* DELDEF: IEFBR14 - Delete prior report output file
//*   Removes TCATBALF.REPT from a previous run.
//*   DISP=(MOD,DELETE) deletes if exists, no error if not.
//DELDEF   EXEC PGM=IEFBR14
//THEFILE  DD DISP=(MOD,DELETE),
//         UNIT=SYSDA,
//         SPACE=(TRK,(1,1),RLSE),
//         DSN=AWS.M2.CARDDEMO.TCATBALF.REPT
//* ********************************************************`***********        
//* Unload the processed transaction category balance file                      
//* *******************************************************************         
//* STEP05R: REPROC - Unload TCATBALF VSAM to sequential
//*   Uses cataloged procedure REPROC from CNTL library
//*   to REPRO VSAM KSDS records to a GDG generation.
//*   PRC001.FILEIN: Source VSAM KSDS (TCATBALF)
//*   PRC001.FILEOUT: GDG output TCATBALF.BKUP(+1)
//*     LRECL=50, RECFM=FB
//STEP05R EXEC PROC=REPROC,                                                     
// CNTLLIB=AWS.M2.CARDDEMO.CNTL                                                 
//*                                                                             
//PRC001.FILEIN  DD DISP=SHR,                                                   
//        DSN=AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS                                
//*                                                                             
//PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE),                                    
//        UNIT=SYSDA,                                                           
//        DCB=(LRECL=50,RECFM=FB,BLKSIZE=0),                                   
//        SPACE=(CYL,(1,1),RLSE),                                               
//        DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)                                 
//* *******************************************************************         
//* Filter the TCATBALFions for a the parm date and sort by card num            
//* *******************************************************************         
//* STEP10R: SORT - Format and sort for printable report
//*   SYMNAMES: Define symbolic field names for SORT:
//*     TRANCAT-ACCT-ID (pos 1, 11 bytes, ZD)
//*     TRANCAT-TYPE-CD (pos 12, 2 bytes, CH)
//*     TRANCAT-CD (pos 14, 4 bytes, ZD)
//*     TRAN-CAT-BAL (pos 18, 11 bytes, ZD)
//*   SORT: Ascending by acct-id, type, category
//*   OUTREC: Reformat with spaces and edited balance
//*     (EDIT mask TTTTTTTTT.TT for decimal display)
//*   Output: TCATBALF.REPT (LRECL=40, FB)
//STEP10R  EXEC PGM=SORT                                                        
//SORTIN   DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.TCATBALF.BKUP(+1)                                
//SYMNAMES DD *                                                                 
TRANCAT-ACCT-ID,1,11,ZD                                                         
TRANCAT-TYPE-CD,12,2,CH                                                         
TRANCAT-CD,14,4,ZD
TRAN-CAT-BAL,18,11,ZD
//SYSIN    DD *                                                                 
 SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)                 
 OUTREC FIELDS=(TRANCAT-ACCT-ID,X,
     TRANCAT-TYPE-CD,X,
     TRANCAT-CD,X,
     TRAN-CAT-BAL,EDIT=(TTTTTTTTT.TT),9X)
/*                                                                              
//SYSOUT   DD SYSOUT=*                                                          
//SORTOUT  DD DISP=(NEW,CATLG,DELETE),                                          
//         UNIT=SYSDA,                                                          
//         DCB=(LRECL=40,RECFM=FB,BLKSIZE=0),                                   
//         SPACE=(CYL,(1,1),RLSE),                                              
//         DSN=AWS.M2.CARDDEMO.TCATBALF.REPT                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
