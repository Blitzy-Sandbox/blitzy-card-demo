//TRANBKP JOB 'REPRO and Delete Transaction Master',CLASS=A,MSGCLASS=0,         
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
//* JOB: TRANBKP - Transaction Master Backup and Rebuild
//* Step 1: REPRO transaction VSAM data to GDG backup
//* Step 2: DELETE existing VSAM cluster and AIX
//* Step 3: DEFINE new empty VSAM cluster (no AIX rebuild)
//* After running TRANBKP, the TRANSACT VSAM is empty.
//* Typically followed by COMBTRAN.jcl to reload merged
//* data, or by TRANIDX.jcl to rebuild the AIX.
//* Dataset: AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS
//* Backup: AWS.M2.CARDDEMO.TRANSACT.BKUP(+1) (GDG)
//* Uses: REPROC cataloged procedure for VSAM unload
//JOBLIB JCLLIB ORDER=('AWS.M2.CARDDEMO.PROC')                                  
//* *******************************************************************        
//* Repro the processed transaction file                                       
//* *******************************************************************         
//* STEP05R: REPROC - Backup TRANSACT VSAM to GDG
//*   Uses REPROC procedure with CNTL library for
//*   VSAM-to-sequential unload.
//*   PRC001.FILEIN: TRANSACT VSAM KSDS source
//*   PRC001.FILEOUT: TRANSACT.BKUP(+1) GDG generation
//*     LRECL=350, RECFM=FB
//STEP05R EXEC PROC=REPROC,                                                     
// CNTLLIB=AWS.M2.CARDDEMO.CNTL                                                 
//*                                                                             
//PRC001.FILEIN  DD DISP=SHR,                                                   
//        DSN=AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS                                
//*                                                                             
//PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE),                                    
//        UNIT=SYSDA,                                                           
//        DCB=(LRECL=350,RECFM=FB,BLKSIZE=0),                                   
//        SPACE=(CYL,(1,1),RLSE),                                               
//        DSN=AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)                                 
//* *******************************************************************         
//* DELETE TRANSACATION MASTER VSAM FILE IF ONE ALREADY EXISTS                  
//* *******************************************************************         
//* STEP05: IDCAMS DELETE - Remove base cluster and AIX
//*   Deletes both TRANSACT.VSAM.KSDS cluster and
//*   TRANSACT.VSAM.AIX. IF MAXCC LE 08 for rerun safety.
//STEP05 EXEC PGM=IDCAMS                                                        
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS -                                  
          CLUSTER                                                               
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
   DELETE AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX -                                   
          ALTERNATEINDEX                                                        
   IF MAXCC LE 08 THEN SET MAXCC = 0                                            
/*                                                                              
//*                                                                             
//* *******************************************************************         
//* DEFINE TRANSACATION MASTER VSAM FILE                                        
//* *******************************************************************         
//* STEP10: IDCAMS DEFINE CLUSTER - Recreate empty KSDS
//*   COND=(4,LT): Skip if prior step returned RC>4
//*   Redefines TRANSACT.VSAM.KSDS with same attributes
//*   as TRANFILE.jcl (KEYS(16 0), RECORDSIZE(350 350))
//*   NOTE: Does NOT rebuild AIX or reload data.
//*   Run TRANIDX.jcl after to rebuild AIX, then
//*   COMBTRAN.jcl to reload merged transaction data.
//STEP10 EXEC PGM=IDCAMS,COND=(4,LT)                                            
//SYSPRINT DD   SYSOUT=*                                                        
//SYSIN    DD   *                                                               
   DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) -                   
          CYLINDERS(1 5) -                                                      
          VOLUMES(AWSHJ1 -                                                      
          ) -                                                                   
          KEYS(16 0) -                                                          
          RECORDSIZE(350 350) -                                                 
          SHAREOPTIONS(2 3) -                                 
          ERASE -                                                               
          INDEXED -                                                             
          ) -                                                                   
          DATA (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.DATA) -                 
          ) -                                                                   
          INDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS.INDEX) -               
          )                                                                     
/*                                                                              
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:08 CDT
//*
