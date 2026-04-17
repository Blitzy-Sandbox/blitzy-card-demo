//DEFCUST JOB 'Define Customer Data File',CLASS=A,MSGCLASS=0,
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
//*
//* JOB: DEFCUST - Primitive Customer Dataset Definition
//* Defines a customer VSAM KSDS cluster. This appears to
//* be an early prototype or draft job that predates the
//* standard provisioning pattern used by CUSTFILE.jcl.
//* KNOWN ISSUES (documented as-is, not corrected):
//*   1. DELETE targets AWS.CCDA.CUSTDATA.CLUSTER but
//*      DEFINE creates AWS.CUSTDATA.CLUSTER (mismatch)
//*   2. Neither dataset name follows the standard
//*      AWS.M2.CARDDEMO.* naming convention
//*   3. Both steps share the name STEP05 (duplicate)
//*   4. No REPRO step to load seed data
//*   5. Uses SHAREOPTIONS(1 4) instead of (2 3)
//* Preferred alternative: CUSTFILE.jcl
//*
//* *******************************************************************
//* DELETE CUSTOMER VSAM FILE IF ONE ALREADY EXISTS
//* *******************************************************************
//* STEP05 (first): IDCAMS DELETE
//*   Attempts to delete cluster AWS.CCDA.CUSTDATA.CLUSTER.
//*   SYSPRINT DD: IDCAMS message output to SYSOUT
//*   SYSIN DD: Inline IDCAMS DELETE control statement
//*   NOTE: No SET MAXCC=0 so job will fail (RC=8) if
//*   the cluster does not already exist.
//* *******************************************************************
//STEP05 EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DELETE AWS.CCDA.CUSTDATA.CLUSTER -
          CLUSTER                     
/*
//*
//* *******************************************************************
//* DELETE CUSTOMER VSAM FILE IF ONE ALREADY EXISTS
//* *******************************************************************
//* STEP05 (second, duplicate name): IDCAMS DEFINE CLUSTER
//*   Defines VSAM KSDS cluster AWS.CUSTDATA.CLUSTER.
//*   SYSPRINT DD: IDCAMS message output to SYSOUT
//*   SYSIN DD: Inline IDCAMS DEFINE CLUSTER statement
//*   NOTE: Different HLQ than DELETE step above (mismatch).
//*   KEYS(10 0) = 10-byte key starting at offset 0.
//*   RECORDSIZE(500 500) = fixed 500-byte customer records.
//*   CYLINDERS(1 5) = primary 1 cyl, secondary 5 cyls.
//*   SHAREOPTIONS(1 4) = exclusive control (non-standard).
//*   ERASE = zero-fill on delete for data security.
//*   INDEXED = VSAM KSDS (key-sequenced) organization.
//*   DATA/INDEX = explicit component naming convention.
//*   NOTE: Section comment above says DELETE but this
//*   step actually performs a DEFINE CLUSTER.
//* *******************************************************************
//STEP05 EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
   DEFINE CLUSTER (NAME(AWS.CUSTDATA.CLUSTER) - 
          CYLINDERS(1 5) -                      
          KEYS(10 0) -                          
          RECORDSIZE(500 500) -                 
          SHAREOPTIONS(1 4) -                   
          ERASE -                               
          INDEXED -                             
          ) -                                        
          DATA (NAME(AWS.CUSTDATA.CLUSTER.DATA) -    
          ) -                                        
          INDEX (NAME(AWS.CUSTDATA.CLUSTER.INDEX) -  
          )                                             
/*
