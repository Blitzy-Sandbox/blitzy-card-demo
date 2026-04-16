//READACCT JOB 'Read account Data',CLASS=A,MSGCLASS=0,
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
//* JOB: READACCT - Account File Diagnostic Read Utility
//* Executes batch COBOL program CBACT01C to sequentially
//* read and display all records from the Account Master
//* VSAM KSDS file. Used for validation, debugging, and
//* data verification after provisioning or updates.
//* Related program source: app/cbl/CBACT01C.cbl
//* Related dataset: AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS
//* *******************************************************************         
//* RUN THE PROGRAM THAT READS THE ACCOUNT MASTER VSAM FILE                     
//* *******************************************************************         
//* STEP05: Execute CBACT01C - reads ACCTDATA VSAM KSDS
//*   sequentially and writes record contents to SYSOUT
//STEP05 EXEC PGM=CBACT01C                                                      
//STEPLIB  DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.LOADLIB                                          
//*   STEPLIB  - CardDemo compiled load module library
//ACCTFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS                               
//*   ACCTFILE - Account Master VSAM KSDS (input, read-only)
//*              300-byte records, key=11 bytes at offset 0
//*              Layout: CVACT01Y.cpy
//SYSOUT   DD SYSOUT=*                                                          
//SYSPRINT DD SYSOUT=*                                                          
//*   SYSOUT   - Program display output (DISPLAY statements)
//*   SYSPRINT - System messages and status output
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:07 CDT
//*
