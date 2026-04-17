//DALYREJS JOB 'DEF GDG FOR REJS',CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID     
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
//* JOB: DALYREJS - Define GDG Base for Daily Rejections
//* Defines a GDG base for daily transaction rejection
//* files. The DALYREJS GDG stores rejected transaction
//* records produced by POSTTRAN (CBTRN02C) during daily
//* transaction posting. Each run creates a new generation.
//* Prerequisite for: POSTTRAN.jcl (writes DALYREJS(+1))
//* GDG settings: LIMIT(5), SCRATCH
//* NOTE: Existing section comment below is a copy-paste
//*   artifact and does not match the actual operation.
//* *******************************************************************         
//* DELETE TRANSACATION MASTER VSAM FILE IF ONE ALREADY EXISTS                  
//* *******************************************************************         
//* STEP05: IDCAMS - Define GDG base AWS.M2.CARDDEMO.DALYREJS
//*   LIMIT(5): Retains up to 5 generations
//*   SCRATCH: Oldest generation is scratched when limit
//*   is exceeded (disk space reclaimed)
//STEP05 EXEC PGM=IDCAMS                                                        
//* SYSPRINT: IDCAMS diagnostic and status messages
//SYSPRINT DD   SYSOUT=*                                                        
//* SYSIN: IDCAMS control statements (inline)
//SYSIN    DD   *                                                               
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.DALYREJS) -                                            
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:05 CDT
//*
