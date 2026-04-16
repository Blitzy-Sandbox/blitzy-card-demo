      ******************************************************************
      * Communication area for CardDemo application programs
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
      *
      * Navigation/Contract Copybook: Central COMMAREA structure
      * --------------------------------------------------------
      * This is the inter-program communication contract used
      * by ALL online CICS CardDemo programs. It is passed as
      * the COMMAREA parameter on every EXEC CICS XCTL and
      * EXEC CICS RETURN TRANSID call.
      *
      * Total size: 160 bytes
      *
      * The COMMAREA enables pseudo-conversational state
      * management in CICS -- each program stores its state
      * here before returning control to CICS, and reads it
      * back on re-entry.
      *
      * Consuming programs: All 17 online CICS programs
      *   COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC,
      *   COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C,
      *   COTRN02C, COBIL00C, CORPT00C, COUSR00C, COUSR01C,
      *   COUSR02C, COUSR03C
      *
      * Cross-references:
      *   Sign-on populates:  app/cbl/COSGN00C.cbl
      *   Menu routing reads: app/cbl/COMEN01C.cbl,
      *                       app/cbl/COADM01C.cbl
      *   Menu tables:        app/cpy/COMEN02Y.cpy,
      *                       app/cpy/COADM02Y.cpy
      *   User security:      app/cpy/CSUSR01Y.cpy
      *                       (SEC-USR-TYPE -> CDEMO-USER-TYPE)
      *   Work areas:         app/cpy/CVCRD01Y.cpy
      *
      ******************************************************************
       01 CARDDEMO-COMMAREA.
      *
      * --- GENERAL-INFO: Program routing and session context --
      * The FROM/TO pairs track navigation history between
      * programs. PGM-CONTEXT distinguishes first entry
      * (0 = display initial screen) from re-entry
      * (1 = process user input).
      *
          05 CDEMO-GENERAL-INFO.
      * Originating CICS transaction ID (bytes 1-4)
             10 CDEMO-FROM-TRANID             PIC X(04).
      * Originating program name (bytes 5-12)
             10 CDEMO-FROM-PROGRAM            PIC X(08).
      * Target CICS transaction ID (bytes 13-16)
             10 CDEMO-TO-TRANID               PIC X(04).
      * Target program name (bytes 17-24)
             10 CDEMO-TO-PROGRAM              PIC X(08).
      * Authenticated user ID from sign-on (bytes 25-32)
             10 CDEMO-USER-ID                 PIC X(08).
      * User type indicator (byte 33): 'A'=Admin, 'U'=User
             10 CDEMO-USER-TYPE               PIC X(01).
      * Admin user -- set by COSGN00C when
      *   SEC-USR-TYPE = 'A'. Routes to admin menu (COADM01C)
                88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
      * Regular user -- set by COSGN00C when
      *   SEC-USR-TYPE = 'U'. Routes to main menu (COMEN01C)
                88 CDEMO-USRTYP-USER          VALUE 'U'.
      * Program entry context flag (byte 34)
             10 CDEMO-PGM-CONTEXT             PIC 9(01).
      * First-time entry -- display initial screen,
      *   no input processing
                88 CDEMO-PGM-ENTER            VALUE 0.
      * Re-entry after user submitted input --
      *   process the BMS map data via RECEIVE MAP
                88 CDEMO-PGM-REENTER          VALUE 1.
      *
      * --- CUSTOMER-INFO: Selected customer context ---------
      * Passed between card, account, and transaction screens
      * to maintain entity selection across programs.
      *
          05 CDEMO-CUSTOMER-INFO.
      * Selected customer ID (bytes 35-43)
             10 CDEMO-CUST-ID                 PIC 9(09).
      * Customer first name (bytes 44-68)
             10 CDEMO-CUST-FNAME              PIC X(25).
      * Customer middle name (bytes 69-93)
             10 CDEMO-CUST-MNAME              PIC X(25).
      * Customer last name (bytes 94-118)
             10 CDEMO-CUST-LNAME              PIC X(25).
      *
      * --- ACCOUNT-INFO: Selected account context -----------
      * Used by account-related screens (COACTVWC, COACTUPC)
      *
          05 CDEMO-ACCOUNT-INFO.
      * Selected account ID (bytes 119-129)
             10 CDEMO-ACCT-ID                 PIC 9(11).
      * Account status indicator (byte 130)
             10 CDEMO-ACCT-STATUS             PIC X(01).
      *
      * --- CARD-INFO: Selected card context -----------------
      * Used by card-related operations (COCRDSLC, COCRDUPC)
      *
          05 CDEMO-CARD-INFO.
      * Selected card number (bytes 131-146)
             10 CDEMO-CARD-NUM                PIC 9(16).
      *
      * --- MORE-INFO: Screen state for restoration ----------
      * Stores last map/mapset displayed so programs can
      * restore the correct screen on re-entry.
      *
          05 CDEMO-MORE-INFO.
      * Last BMS map name displayed (bytes 147-153)
             10  CDEMO-LAST-MAP               PIC X(7).
      * Last BMS mapset name used (bytes 154-160)
             10  CDEMO-LAST-MAPSET            PIC X(7).
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:57 CDT
      *
