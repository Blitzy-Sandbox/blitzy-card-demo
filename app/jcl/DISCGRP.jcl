//DISCGRP JOB 'DEFINE DISCLOSURE GROUP FILE',CLASS=A,MSGCLASS=0,                
//  NOTIFY=&SYSUID                                                
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
//*
//* JOB: DISCGRP - Provision Disclosure Group Reference File
//* Deletes, defines, and loads the Disclosure Group VSAM
//* KSDS file. Contains disclosure group codes and interest
//* rate parameters used by interest calculation processing.
//* Dataset: AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
//* KSDS key: 16 bytes at offset 0 (disclosure group code)
//* Record size: 50 bytes (fixed)
//* Seed data: AWS.M2.CARDDEMO.DISCGRP.PS
//* Copybook layout: CVTRA02Y.cpy
//* Consumed by: INTCALC (CBACT04C) for rate lookup
//*
//* *******************************************************************         
//* DELETE DISCLOSURE GROUP VSAM FILE IF ONE ALREADY EXISTS                     
//* *******************************************************************         
//* STEP05: IDCAMS DELETE - Remove existing DISCGRP cluster
//*         for idempotent rerun. SET MAXCC=0 suppresses
//*         RC=8 when the cluster does not yet exist.
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS -                                   
          CLUSTER                                                               
   SET    MAXCC = 0                                                             
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE DISCLOSURE GROUP VSAM FILE                                           
//* *******************************************************************         
//* STEP10: IDCAMS DEFINE CLUSTER - Create disclosure group
//*         VSAM KSDS. KEYS(16 0) = 16-byte disclosure group
//*         code key at offset 0. RECORDSIZE(50 50) fixed.
//*         SHAREOPTIONS(2 3) allows cross-region read sharing.
//STEP10 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS) -                    
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(16 0) -                                                          
          RECORDSIZE(50 50) -                                                   
          SHAREOPTIONS(2 3) -                                                   
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS.DATA) -                  
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS.INDEX) -                
          )                                                                     
/*                                                                              
//* *******************************************************************         
//* COPY DATA FROM FLAT FILE TO VSAM FILE                                       
//* *******************************************************************         
//* STEP15: IDCAMS REPRO - Load disclosure group seed data
//*         from sequential flat file into VSAM KSDS.
//STEP15 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//* DISCGRP DD: Input flat file containing seed data (SHR)
//DISCGRP DD DISP=SHR,                                                          
//         DSN=AWS.M2.CARDDEMO.DISCGRP.PS                                       
//* DISCVSAM DD: Output VSAM KSDS target dataset (OLD)
//DISCVSAM DD DISP=OLD,                                                         
//         DSN=AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS                                
//SYSIN    DD   *                                                               
   REPRO INFILE(DISCGRP) OUTFILE(DISCVSAM)                                      
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
