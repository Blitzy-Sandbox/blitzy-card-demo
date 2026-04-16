//READXREF JOB 'Read Cross Ref file',CLASS=A,MSGCLASS=0,
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
//* JOB: READXREF - Cross-Reference File Diagnostic Read
//* Executes batch COBOL program CBACT03C to sequentially
//* read and display all records from the Card-Account
//* Cross-Reference VSAM KSDS file. Used for validation
//* and debugging after provisioning with XREFFILE.jcl.
//* Related program source: app/cbl/CBACT03C.cbl
//* Related dataset: AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS
//* *******************************************************************         
//* RUN THE PROGRAM THAT READS THE XREF MASTER VSAM FILE                        
//* *******************************************************************         
//* STEP05: Execute CBACT03C - reads CARDXREF VSAM KSDS
//*   sequentially and writes record contents to SYSOUT
//STEP05 EXEC PGM=CBACT03C                                                      
//* STEPLIB: CardDemo compiled load module library
//STEPLIB  DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.LOADLIB                                          
//* XREFFILE: Card-Account Cross-Reference VSAM KSDS
//*   input (read-only), 50-byte records, key=16 bytes
//*   at offset 0. Record layout: CVACT03Y.cpy
//XREFFILE DD DISP=SHR,                                                         
//         DSN=AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS                               
//* SYSOUT: Displays CBACT03C program output messages
//SYSOUT   DD SYSOUT=*                                                          
//* SYSPRINT: Captures printed report output to spool
//SYSPRINT DD SYSOUT=*                                                          
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:07 CDT
//*
