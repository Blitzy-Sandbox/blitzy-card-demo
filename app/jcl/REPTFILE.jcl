//REPTFILE JOB 'DEF GDG FOR REPORT FILE',                                       
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
//* JOB: REPTFILE - Define GDG Base for Transaction Reports
//* Defines a GDG base for formatted transaction report
//* output files. The TRANREPT GDG stores reports produced
//* by TRANREPT.jcl (CBTRN03C) batch processing.
//* Prerequisite for: TRANREPT.jcl (writes TRANREPT(+1))
//* GDG settings: LIMIT(10) - higher limit than other GDGs
//*   to retain more report history
//* NOTE: Existing section comment below is a copy-paste
//*   artifact from another job.
//* *******************************************************************         
//* DELETE TRANSACATION MASTER VSAM FILE IF ONE ALREADY EXISTS                  
//* *******************************************************************         
//* STEP05: IDCAMS - Define GDG base AWS.M2.CARDDEMO.TRANREPT
//*   LIMIT(10): Retains up to 10 generations of reports
//*   No SCRATCH keyword: generations are not auto-deleted
//STEP05 EXEC PGM=IDCAMS                                                        
//*   DD SYSPRINT: IDCAMS diagnostic and status output
//SYSPRINT DD   SYSOUT=*                                                        
//*   DD SYSIN: IDCAMS control statements (inline)
//SYSIN    DD   *                                                               
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TRANREPT) -                                            
    LIMIT(10) -                                                                 
   )                                                                            
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:07 CDT
//*
