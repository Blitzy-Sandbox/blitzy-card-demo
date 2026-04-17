//CLOSEFIL JOB 'Close files in CICS',CLASS=A,MSGCLASS=0,
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
//* JOB: CLOSEFIL - Close CICS File Resources
//* Closes CardDemo VSAM file resources in the CICS
//* region CICSAWSA using SDSF batch command submission.
//* Files must be closed before VSAM datasets can be
//* deleted and redefined by provisioning jobs.
//* Run order: Execute before dataset rebuild jobs
//*   (ACCTFILE, CARDFILE, CUSTFILE, XREFFILE, TRANFILE)
//* Companion job: OPENFIL.jcl (reopens files after rebuild)
//*********************************************************************         
//* Close files in CICS region                                                  
//*********************************************************************         
//* CLCIFIL: SDSF - Submit CEMT commands to CICS region
//*   Uses /F (modify) MVS command to send CICS CEMT
//*   transactions to the CICSAWSA region
//CLCIFIL EXEC PGM=SDSF                                                         
//* ISFOUT: SDSF output messages (captures SDSF responses)
//ISFOUT DD SYSOUT=*                                                            
//* CMDOUT: SDSF command response output (CEMT results)
//CMDOUT DD SYSOUT=*                                                            
//* ISFIN: SDSF command input stream (inline data)
//*   Contains 5 MVS /F (modify) commands targeting CICSAWSA
//*   Closes 5 CICS file definitions:
//*     TRANSACT - Transaction master VSAM file
//*     CCXREF   - Card-account cross-reference file
//*     ACCTDAT  - Account data VSAM file
//*     CXACAIX  - Cross-reference alternate index path
//*     USRSEC   - User security VSAM file
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(TRANSACT ) CLO'                                      
 /F CICSAWSA,'CEMT SET FIL(CCXREF ) CLO'                                        
 /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) CLO'                                       
 /F CICSAWSA,'CEMT SET FIL(CXACAIX ) CLO'                                       
 /F CICSAWSA,'CEMT SET FIL(USRSEC ) CLO'                                       
/*      
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:05 CDT
//*
