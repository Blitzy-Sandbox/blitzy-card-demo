//OEPNFIL JOB 'Open files in CICS',CLASS=A,MSGCLASS=0,
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
//* JOB: OPENFIL - Open CICS File Resources
//* Opens CardDemo VSAM file resources in the CICS
//* region CICSAWSA using SDSF batch command submission.
//* Files must be opened after VSAM datasets have been
//* rebuilt by provisioning jobs so CICS programs can
//* access them for online transaction processing.
//* Run order: Execute after dataset rebuild/provision jobs
//* Companion job: CLOSEFIL.jcl (closes files before rebuild)
//* NOTE: JOB card name 'OEPNFIL' contains a typo
//*   (should be 'OPENFIL'). This is the original code.
//*
//*********************************************************************         
//* Open files in CICS region                                                  
//*********************************************************************         
//* OPCIFIL: SDSF - Submit CEMT commands to CICS region
//*   Uses /F (modify) MVS command to send CICS CEMT
//*   transactions to the CICSAWSA region
//OPCIFIL EXEC PGM=SDSF                                                         
//* ISFOUT: SDSF output messages (job log/messages)
//ISFOUT DD SYSOUT=*                                                            
//* CMDOUT: SDSF command response output
//CMDOUT DD SYSOUT=*                                                            
//* ISFIN: SDSF command input stream
//*   Opens 5 CICS file definitions:
//*     TRANSACT - Transaction master VSAM file
//*     CCXREF   - Card-account cross-reference file
//*     ACCTDAT  - Account data VSAM file
//*     CXACAIX  - Cross-reference alternate index path
//*     USRSEC   - User security VSAM file
//ISFIN  DD *                                                                   
 /F CICSAWSA,'CEMT SET FIL(TRANSACT ) OPE'                                      
 /F CICSAWSA,'CEMT SET FIL(CCXREF ) OPE'                                        
 /F CICSAWSA,'CEMT SET FIL(ACCTDAT ) OPE'                                       
 /F CICSAWSA,'CEMT SET FIL(CXACAIX ) OPE'                                       
 /F CICSAWSA,'CEMT SET FIL(USRSEC ) OPE'                                       
/*      
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
