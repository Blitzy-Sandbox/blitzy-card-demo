      ******************************************************************
      * CardDemo - Admin Menu Options
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
      * Navigation/Contract Copybook: Admin menu program
      * routing table.
      *
      * Defines the 4-option admin/security menu structure
      * used by COADM01C.cbl (admin menu controller). Each
      * entry maps an admin option number to a display label
      * and a target CICS program name for XCTL navigation.
      *
      * Admin menu is only accessible to users with
      * CDEMO-USRTYP-ADMIN (type 'A') from COCOM01Y.cpy.
      * Unlike COMEN02Y.cpy (main menu), this table has no
      * per-entry user-type field because all admin menu
      * options are admin-only.
      *
      * Cross-References:
      *   Consumer: COADM01C.cbl (admin menu controller)
      *   Main menu: COMEN02Y.cpy (10-entry main menu)
      *   COMMAREA: COCOM01Y.cpy (user-type gating)
      *   User record: CSUSR01Y.cpy (security layout)
      *
       01 CARDDEMO-ADMIN-MENU-OPTIONS.
      * Number of active admin menu options (currently 4)
         05 CDEMO-ADMIN-OPT-COUNT           PIC 9(02) VALUE 4.

      * Sequential FILLER data for 4 admin menu entries.
      * Each entry is 45 bytes:
      *   2-byte option number  (PIC 9(02))
      * + 35-byte display label (PIC X(35))
      * + 8-byte program name   (PIC X(08))
      * Total: 4 x 45 = 180 bytes.
      * REDEFINES overlay below allows indexed access
      * with OCCURS 9 (5 spare slots for expansion).
         05 CDEMO-ADMIN-OPTIONS-DATA.

      * Option 1: User List (Security) -> COUSR00C
      *   Browses security user records from USRSEC file
           10 FILLER                        PIC 9(02) VALUE 1.
           10 FILLER                        PIC X(35) VALUE
               'User List (Security)               '.
           10 FILLER                        PIC X(08) VALUE 'COUSR00C'.

      * Option 2: User Add (Security) -> COUSR01C
      *   Adds a new security user record to USRSEC file
           10 FILLER                        PIC 9(02) VALUE 2.
           10 FILLER                        PIC X(35) VALUE
               'User Add (Security)                '.
           10 FILLER                        PIC X(08) VALUE 'COUSR01C'.

      * Option 3: User Update (Security) -> COUSR02C
      *   Updates an existing security user in USRSEC
           10 FILLER                        PIC 9(02) VALUE 3.
           10 FILLER                        PIC X(35) VALUE
               'User Update (Security)             '.
           10 FILLER                        PIC X(08) VALUE 'COUSR02C'.

      * Option 4: User Delete (Security) -> COUSR03C
      *   Deletes a security user record from USRSEC
           10 FILLER                        PIC 9(02) VALUE 4.
           10 FILLER                        PIC X(35) VALUE
               'User Delete (Security)             '.
           10 FILLER                        PIC X(08) VALUE 'COUSR03C'.

      * REDEFINES overlay: Maps the sequential FILLER
      * data into an indexable array for navigation logic.
      * OCCURS 9 allows up to 9 options (5 beyond current
      * 4 entries reserved for future expansion).
         05 CDEMO-ADMIN-OPTIONS REDEFINES CDEMO-ADMIN-OPTIONS-DATA.
           10 CDEMO-ADMIN-OPT OCCURS 9 TIMES.
      * Option number (matches FILLER VALUE above)
             15 CDEMO-ADMIN-OPT-NUM           PIC 9(02).
      * Display label shown on admin menu screen
             15 CDEMO-ADMIN-OPT-NAME          PIC X(35).
      * Target CICS program name for XCTL transfer
             15 CDEMO-ADMIN-OPT-PGMNAME       PIC X(08).
      *
      * Ver: CardDemo_v1.0-26-g42273c1-79 Date: 2022-07-20 16:59:12 CDT
      *
