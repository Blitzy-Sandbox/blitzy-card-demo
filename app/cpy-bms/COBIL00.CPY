      ******************************************************************
      * Copyright Amazon.com, Inc. or its affiliates.                   
      * All Rights Reserved.                                            
      *                                                                 
      * Licensed under the Apache License, Version 2.0 (the "License"). 
      * You may not use this file except in compliance with the License.
      * You may obtain a copy of the License at                         
      *                                                                 
      *    http://www.apache.org/licenses/LICENSE-2.0                   
      *                                                                 
      * Unless required by applicable law or agreed to in writing,      
      * software distributed under the License is distributed on an     
      * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    
      * either express or implied. See the License for the specific     
      * language governing permissions and limitations under the License
      ****************************************************************** 
      * Symbolic map copybook for the Bill Payment screen.
      * BMS Source: app/bms/COBIL00.bms (Mapset: COBIL00, Map: COBIL0A)
      * COBOL Program: app/cbl/COBIL00C.cbl (Transaction: CB00)
      * Compact confirmation screen: user enters account ID, sees
      * current balance, and confirms payment (Y/N). COBIL00C
      * updates account balance and writes a transaction record.
      * ---------------------------------------------------------------
      * Input buffer layout (AI) -- populated by EXEC CICS RECEIVE MAP.
      * Fields use L/F/A/I suffixes:
      *   L = Length (COMP PIC S9(4))  F = Flag (PICTURE X)
      *   A = Attribute (PICTURE X)    I = Input data (PICTURE X(n))
      * ---------------------------------------------------------------
       01  COBIL0AI.
      * 12-byte TIOA prefix -- required by TIOAPFX=YES
           02  FILLER PIC X(12).
      * --- Header fields: transaction ID, titles, date/time ---
           02  TRNNAMEL    COMP  PIC  S9(4).
           02  TRNNAMEF    PICTURE X.
           02  FILLER REDEFINES TRNNAMEF.
             03 TRNNAMEA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  TRNNAMEI  PIC X(4).
           02  TITLE01L    COMP  PIC  S9(4).
           02  TITLE01F    PICTURE X.
           02  FILLER REDEFINES TITLE01F.
             03 TITLE01A    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  TITLE01I  PIC X(40).
           02  CURDATEL    COMP  PIC  S9(4).
           02  CURDATEF    PICTURE X.
           02  FILLER REDEFINES CURDATEF.
             03 CURDATEA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  CURDATEI  PIC X(8).
           02  PGMNAMEL    COMP  PIC  S9(4).
           02  PGMNAMEF    PICTURE X.
           02  FILLER REDEFINES PGMNAMEF.
             03 PGMNAMEA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  PGMNAMEI  PIC X(8).
           02  TITLE02L    COMP  PIC  S9(4).
           02  TITLE02F    PICTURE X.
           02  FILLER REDEFINES TITLE02F.
             03 TITLE02A    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  TITLE02I  PIC X(40).
           02  CURTIMEL    COMP  PIC  S9(4).
           02  CURTIMEF    PICTURE X.
           02  FILLER REDEFINES CURTIMEF.
             03 CURTIMEA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  CURTIMEI  PIC X(8).
      * --- Account ID entry for balance lookup ---
           02  ACTIDINL    COMP  PIC  S9(4).
           02  ACTIDINF    PICTURE X.
           02  FILLER REDEFINES ACTIDINF.
             03 ACTIDINA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  ACTIDINI  PIC X(11).
      * --- Current balance display (populated from ACCTDAT) ---
           02  CURBALL    COMP  PIC  S9(4).
           02  CURBALF    PICTURE X.
           02  FILLER REDEFINES CURBALF.
             03 CURBALA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  CURBALI  PIC X(14).
      * --- Payment confirmation (Y/N) ---
           02  CONFIRML    COMP  PIC  S9(4).
           02  CONFIRMF    PICTURE X.
           02  FILLER REDEFINES CONFIRMF.
             03 CONFIRMA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  CONFIRMI  PIC X(1).
      * --- Error message area (78-char, RED) ---
           02  ERRMSGL    COMP  PIC  S9(4).
           02  ERRMSGF    PICTURE X.
           02  FILLER REDEFINES ERRMSGF.
             03 ERRMSGA    PICTURE X.
           02  FILLER   PICTURE X(4).
           02  ERRMSGI  PIC X(78).
      * ---------------------------------------------------------------
      * Output buffer layout (AO) -- populated by program for
      * EXEC CICS SEND MAP. REDEFINES the AI layout.
      * Fields use C/P/H/V/O suffixes:
      *   C = Color    P = Programmed symbol   H = Highlighting
      *   V = Validation   O = Output data (REDEFINES I field)
      * ---------------------------------------------------------------
       01  COBIL0AO REDEFINES COBIL0AI.
           02  FILLER PIC X(12).
      * --- Header fields: transaction ID, titles, date/time ---
           02  FILLER PICTURE X(3).
           02  TRNNAMEC    PICTURE X.
           02  TRNNAMEP    PICTURE X.
           02  TRNNAMEH    PICTURE X.
           02  TRNNAMEV    PICTURE X.
           02  TRNNAMEO  PIC X(4).
           02  FILLER PICTURE X(3).
           02  TITLE01C    PICTURE X.
           02  TITLE01P    PICTURE X.
           02  TITLE01H    PICTURE X.
           02  TITLE01V    PICTURE X.
           02  TITLE01O  PIC X(40).
           02  FILLER PICTURE X(3).
           02  CURDATEC    PICTURE X.
           02  CURDATEP    PICTURE X.
           02  CURDATEH    PICTURE X.
           02  CURDATEV    PICTURE X.
           02  CURDATEO  PIC X(8).
           02  FILLER PICTURE X(3).
           02  PGMNAMEC    PICTURE X.
           02  PGMNAMEP    PICTURE X.
           02  PGMNAMEH    PICTURE X.
           02  PGMNAMEV    PICTURE X.
           02  PGMNAMEO  PIC X(8).
           02  FILLER PICTURE X(3).
           02  TITLE02C    PICTURE X.
           02  TITLE02P    PICTURE X.
           02  TITLE02H    PICTURE X.
           02  TITLE02V    PICTURE X.
           02  TITLE02O  PIC X(40).
           02  FILLER PICTURE X(3).
           02  CURTIMEC    PICTURE X.
           02  CURTIMEP    PICTURE X.
           02  CURTIMEH    PICTURE X.
           02  CURTIMEV    PICTURE X.
           02  CURTIMEO  PIC X(8).
      * --- Account ID entry for balance lookup ---
           02  FILLER PICTURE X(3).
           02  ACTIDINC    PICTURE X.
           02  ACTIDINP    PICTURE X.
           02  ACTIDINH    PICTURE X.
           02  ACTIDINV    PICTURE X.
           02  ACTIDINO  PIC X(11).
      * --- Current balance display (populated from ACCTDAT) ---
           02  FILLER PICTURE X(3).
           02  CURBALC    PICTURE X.
           02  CURBALP    PICTURE X.
           02  CURBALH    PICTURE X.
           02  CURBALV    PICTURE X.
           02  CURBALO  PIC X(14).
      * --- Payment confirmation (Y/N) ---
           02  FILLER PICTURE X(3).
           02  CONFIRMC    PICTURE X.
           02  CONFIRMP    PICTURE X.
           02  CONFIRMH    PICTURE X.
           02  CONFIRMV    PICTURE X.
           02  CONFIRMO  PIC X(1).
      * --- Error message area (78-char, RED) ---
           02  FILLER PICTURE X(3).
           02  ERRMSGC    PICTURE X.
           02  ERRMSGP    PICTURE X.
           02  ERRMSGH    PICTURE X.
           02  ERRMSGV    PICTURE X.
           02  ERRMSGO  PIC X(78).
