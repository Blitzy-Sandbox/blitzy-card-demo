//TRANIDX JOB 'Define AIX on Transaction Master',CLASS=A,MSGCLASS=0,         
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
//* JOB: TRANIDX - Define AIX on Existing Transaction Master
//* Defines the alternate index (AIX) and PATH on the
//* existing TRANSACT.VSAM.KSDS base cluster WITHOUT
//* deleting or redefining the base dataset. Use this
//* when the base cluster is already provisioned but the
//* AIX/PATH need to be created or rebuilt.
//* Step numbers (20/25/30) match TRANFILE.jcl for
//* consistency in the AIX creation sequence.
//* AIX: AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX
//*   KEYS(26 304) = processed timestamp at offset 304
//* PATH: AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH
//* Prerequisite: TRANSACT.VSAM.KSDS must exist
//*   (created by TRANFILE.jcl or TRANBKP.jcl)
//*
//*-------------------------------------------------------------------*         
//* CREATE ALTERNATE INDEX ON PROCESSED TIMESTAMP                               
//*-------------------------------------------------------------------*         
//* STEP20: IDCAMS DEFINE ALTERNATEINDEX
//*   KEYS(26 304) = 26-byte processed timestamp at
//*   offset 304. NONUNIQUEKEY allows duplicate
//*   timestamps. UPGRADE keeps AIX in sync with base.
//*   Identical definition to TRANFILE.jcl STEP20.
//STEP20  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)-              
   RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)                    -              
   KEYS(26 304)                                                  -             
   NONUNIQUEKEY                                                  -              
   UPGRADE                                                       -              
   RECORDSIZE(350,350)                                           -              
   VOLUMES(AWSHJ1)                                               -              
   CYLINDERS(5,1))                                               -              
   DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.DATA))           -              
   INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.INDEX))                        
/*                                                                              
//*-------------------------------------------------------------------*         
//* DEFINE PATH IS USED TO RELATE THE ALTERNATE INDEX TO BASE CLUSTER           
//*-------------------------------------------------------------------*         
//* STEP25: IDCAMS DEFINE PATH - Links AIX to base
//*   cluster for timestamp-based access.
//STEP25  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
  DEFINE PATH                                           -                       
   (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH)        -                       
    PATHENTRY(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX))                               
/*                                                                              
//*------------------------------------------------------------------           
//* BUILD ALTERNATE INDEX CLUSTER                                               
//*-------------------------------------------------------------------*         
//* STEP30: IDCAMS BLDINDEX - Scans base KSDS and
//*   populates AIX entries from existing records.
//STEP30  EXEC PGM=IDCAMS                                                       
//SYSPRINT DD  SYSOUT=*                                                         
//SYSIN    DD  *                                                                
   BLDINDEX                                                      -              
   INDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)                 -              
   OUTDATASET(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)                                
/*  
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
