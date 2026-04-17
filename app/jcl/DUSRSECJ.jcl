//DUSRSECJ JOB 'DEF USRSEC FILE',REGION=8M,CLASS=A,
//      MSGCLASS=H,NOTIFY=&SYSUID
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
//* JOB: DUSRSECJ - Provision User Security File
//* Creates and loads the User Security VSAM KSDS file
//* containing authentication records for CardDemo users.
//* Unique pattern: Seeds data from inline JCL (DD *)
//* rather than an external flat file dataset.
//* Dataset: AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS
//* KSDS key: 8 bytes at offset 0 (user ID)
//* Record size: 80 bytes (fixed)
//* Copybook layout: CSUSR01Y.cpy
//* Consumed by: COSGN00C (login authentication),
//*   COUSR00C-03C (user administration online programs)
//* Inline data: 10 demo users (ADMIN001-005, USER0001-005)
//*   with synthetic credentials (not production secrets)
//*-------------------------------------------------------------------*
//* PRE DELETE STEP
//*-------------------------------------------------------------------*
//*
//* PREDEL: IEFBR14 - Pre-delete sequential staging file
//*   Removes any existing USRSEC.PS from a prior run.
//*   DISP=(MOD,DELETE,DELETE) ensures deletion regardless
//*   of whether the file exists.
//PREDEL  EXEC PGM=IEFBR14
//*
//DD01     DD DSN=AWS.M2.CARDDEMO.USRSEC.PS,
//            DISP=(MOD,DELETE,DELETE)
//*
//*-------------------------------------------------------------------*
//* CREATE USER SECURITY FILE (PS) FROM IN-STREAM DATA
//*-------------------------------------------------------------------*
//*
//* STEP01: IEBGENER - Create PS file from inline data
//*   Copies inline user records from SYSUT1 DD * to a
//*   new sequential PS file (USRSEC.PS).
//*   Record format: 80-byte fixed-block records
//*   Contains 10 user records: 5 admin (type 'A') and
//*   5 regular users (type 'U')
//*   Field layout per CSUSR01Y.cpy:
//*     Pos 1-8:   User ID (e.g., ADMIN001, USER0001)
//*     Pos 9-28:  First name
//*     Pos 29-48: Last name
//*     Pos 49-68: Password
//*     Pos 69:    User type (A=Admin, U=User)
//STEP01  EXEC PGM=IEBGENER
//*
//SYSUT1   DD *
ADMIN001MARGARET            GOLD                PASSWORDA
ADMIN002RUSSELL             RUSSELL             PASSWORDA
ADMIN003RAYMOND             WHITMORE            PASSWORDA
ADMIN004EMMANUEL            CASGRAIN            PASSWORDA
ADMIN005GRANVILLE           LACHAPELLE          PASSWORDA
USER0001LAWRENCE            THOMAS              PASSWORDU
USER0002AJITH               KUMAR               PASSWORDU
USER0003LAURITZ             ALME                PASSWORDU
USER0004AVERARDO            MAZZI               PASSWORDU
USER0005LEE                 TING                PASSWORDU
/*
//SYSUT2   DD DSN=AWS.M2.CARDDEMO.USRSEC.PS,
//            DISP=(NEW,CATLG,DELETE),
//            DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0),
//            UNIT=SYSDA,SPACE=(TRK,(10,5),RLSE)
//*
//SYSPRINT DD SYSOUT=*
//SYSIN    DD DUMMY
//*
//*-------------------------------------------------------------------*
//* DEFINE VSAM FILE FOR USER SECURITY
//*-------------------------------------------------------------------*
//*
//* STEP02: IDCAMS - Delete existing VSAM and define new
//*   DELETE + SET MAXCC=0: Remove prior VSAM cluster
//*   DEFINE CLUSTER: Create USRSEC VSAM KSDS
//*     KEYS(8,0) = 8-byte user ID key at offset 0
//*     RECORDSIZE(80,80) = fixed 80-byte records
//*     REUSE = allows REPRO to reload without redefine
//*     FREESPACE(10,15) = 10% CI, 15% CA free space
//*     CISZ(8192) = 8KB control interval size
//*     TRACKS(45,15) = primary/secondary allocation
//STEP02  EXEC PGM=IDCAMS
//*
//SYSPRINT DD  SYSOUT=*
//SYSIN    DD  *
 DELETE                  AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS
 SET       MAXCC = 0
 DEFINE    CLUSTER (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS)    -
                    KEYS(8,0)                                 -
                    RECORDSIZE(80,80)                         -
                    REUSE                                     -
                    INDEXED                                   -
                    TRACKS(45,15)                             -
                    FREESPACE(10,15)                          -
                    CISZ(8192))                               -
           DATA    (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS.DAT)) -
           INDEX   (NAME(AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS.IDX))
/*
//*
//*-------------------------------------------------------------------*
//* COPY USER SECURITY DATA FROM PS TO VSAM FILE
//*-------------------------------------------------------------------*
//*
//* STEP03: IDCAMS REPRO - Load PS data into VSAM KSDS
//*   Copies all records from the sequential staging file
//*   to the VSAM KSDS cluster.
//STEP03  EXEC PGM=IDCAMS
//*
//IN       DD  DSN=AWS.M2.CARDDEMO.USRSEC.PS,DISP=SHR
//OUT      DD  DSN=AWS.M2.CARDDEMO.USRSEC.VSAM.KSDS,DISP=SHR
//SYSOUT   DD  SYSOUT=*
//SYSPRINT DD  SYSOUT=*
//SYSIN    DD  *
  REPRO INFILE(IN) OUTFILE(OUT)
/*
//
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:06 CDT
//*
