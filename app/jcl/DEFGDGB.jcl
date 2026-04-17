//DEFGDGB JOB 'DEF GDG BASES',CLASS=A,MSGCLASS=0,NOTIFY=&SYSUID      
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
//* JOB: DEFGDGB - Define GDG Bases for CardDemo Project
//* Defines 6 Generation Data Group (GDG) bases used by
//* the batch processing pipeline. GDGs provide automatic
//* generation management for sequential output datasets.
//* All 6 GDGs use LIMIT(5) and SCRATCH to retain up to
//* 5 generations with automatic space reclamation.
//* Run order: Execute before any batch processing jobs.
//* IF LASTCC=12 pattern: Ignores "already exists" errors
//*   to make the job safely rerunnable (idempotent).
//* *******************************************************************         
//*  DEFINE GDG BASES NEEDED BY CARDDEMO PROJECT                                
//* *******************************************************************         
//* STEP05: IDCAMS - Define 6 GDG bases in a single step
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
//*   GDG 1: TRANSACT.BKUP - Transaction master backups
//*     Used by TRANBKP.jcl before rebuilding TRANSACT
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TRANSACT.BKUP) -                                       
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
//*   GDG 2: TRANSACT.DALY - Filtered daily transactions
//*     Used by TRANREPT.jcl SORT step output
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TRANSACT.DALY) -                                       
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
//*   GDG 3: TRANREPT - Formatted transaction reports
//*     Used by TRANREPT.jcl CBTRN03C step output
//*     NOTE: Also defined separately in REPTFILE.jcl
//*     with LIMIT(10)
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TRANREPT) -                                            
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
//*   GDG 4: TCATBALF.BKUP - Category balance backups
//*     Used by PRTCATBL.jcl unload step output
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TCATBALF.BKUP) -                                       
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
//*   GDG 5: SYSTRAN - System-generated transactions
//*     Used by INTCALC.jcl (CBACT04C) output
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.SYSTRAN) -                                             
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
//*   GDG 6: TRANSACT.COMBINED - Merged transaction file
//*     Used by COMBTRAN.jcl SORT output
   DEFINE GENERATIONDATAGROUP -                                                 
   (NAME(AWS.M2.CARDDEMO.TRANSACT.COMBINED) -                                   
    LIMIT(5) -                                                                  
    SCRATCH -                                                                   
   )                                                                            
   IF LASTCC=12 THEN SET MAXCC=0                                                
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:05 CDT
//*
