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
      * Navigation/Contract Copybook: Main menu program routing
      * table.
      * Defines the 10-option main menu structure used by
      * COMEN01C.cbl to map user-selected menu options to target
      * COBOL programs via CICS XCTL.
      *
      * Each entry maps a menu option number to a display label,
      * a target program name, and the required user type.
      *
      * Note: The header text says 'Admin Menu' but this is the
      * MAIN menu copybook. See COADM02Y.cpy for the admin menu.
      *
      * Consumer: COMEN01C.cbl (main menu controller)
      * Related: COCOM01Y.cpy (CDEMO-TO-PROGRAM receives the
      *          selected program name from this table)
      *          COADM02Y.cpy (separate 4-entry admin menu)
      *
      * Table Structure:
      *   Data is stored as sequential FILLER items (literal
      *   layout) then overlaid by a REDEFINES with OCCURS 12
      *   (2 extra slots beyond the active 10 for future use).
      *   Each entry is 46 bytes:
      *     2-byte option number  PIC 9(02)
      *     35-byte display label PIC X(35)
      *     8-byte program name   PIC X(08)
      *     1-byte user type      PIC X(01) U=User A=Admin
      *
       01 CARDDEMO-MAIN-MENU-OPTIONS.
      *
      * Active option count (10 menu choices defined below)
         05 CDEMO-MENU-OPT-COUNT           PIC 9(02) VALUE 10.
      *
      * Literal table data: each group of 4 FILLER items
      * defines one menu option entry (46 bytes per entry).
         05 CDEMO-MENU-OPTIONS-DATA.
      *
      * Option 1: Account View -> COACTVWC (User)
           10 FILLER                       PIC 9(02) VALUE 1.
           10 FILLER                       PIC X(35) VALUE
               'Account View                       '.
           10 FILLER                       PIC X(08) VALUE 'COACTVWC'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 2: Account Update -> COACTUPC (User)
           10 FILLER                       PIC 9(02) VALUE 2.
           10 FILLER                       PIC X(35) VALUE
               'Account Update                     '.
           10 FILLER                       PIC X(08) VALUE 'COACTUPC'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 3: Credit Card List -> COCRDLIC (User)
           10 FILLER                       PIC 9(02) VALUE 3.
           10 FILLER                       PIC X(35) VALUE
               'Credit Card List                   '.
           10 FILLER                       PIC X(08) VALUE 'COCRDLIC'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 4: Credit Card View -> COCRDSLC (User)
           10 FILLER                       PIC 9(02) VALUE 4.
           10 FILLER                       PIC X(35) VALUE
               'Credit Card View                   '.
           10 FILLER                       PIC X(08) VALUE 'COCRDSLC'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 5: Credit Card Update -> COCRDUPC (User)
           10 FILLER                       PIC 9(02) VALUE 5.
           10 FILLER                       PIC X(35) VALUE
               'Credit Card Update                 '.
           10 FILLER                       PIC X(08) VALUE 'COCRDUPC'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 6: Transaction List -> COTRN00C (User)
           10 FILLER                       PIC 9(02) VALUE 6.
           10 FILLER                       PIC X(35) VALUE
               'Transaction List                   '.
           10 FILLER                       PIC X(08) VALUE 'COTRN00C'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 7: Transaction View -> COTRN01C (User)
           10 FILLER                       PIC 9(02) VALUE 7.
           10 FILLER                       PIC X(35) VALUE
               'Transaction View                   '.
           10 FILLER                       PIC X(08) VALUE 'COTRN01C'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 8: Transaction Add -> COTRN02C (User)
      * (Originally labeled 'Transaction Add (Admin Only)'
      *  per the commented-out line below; later changed
      *  to allow all users)
           10 FILLER                        PIC 9(02) VALUE 8.
           10 FILLER                       PIC X(35) VALUE
      *        'Transaction Add (Admin Only)       '.
               'Transaction Add                    '.
           10 FILLER                       PIC X(08) VALUE 'COTRN02C'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 9: Transaction Reports -> CORPT00C (User)
           10 FILLER                       PIC 9(02) VALUE 9.
           10 FILLER                       PIC X(35) VALUE
               'Transaction Reports                '.
           10 FILLER                       PIC X(08) VALUE 'CORPT00C'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * Option 10: Bill Payment -> COBIL00C (User)
           10 FILLER                       PIC 9(02) VALUE 10.
           10 FILLER                       PIC X(35) VALUE
               'Bill Payment                       '.
           10 FILLER                       PIC X(08) VALUE 'COBIL00C'.
           10 FILLER                       PIC X(01) VALUE 'U'.
      *
      * REDEFINES overlay: maps the literal FILLER data above
      * into an indexable table with OCCURS 12 (10 active
      * entries plus 2 reserved expansion slots).
      * Programs access options via subscript:
      *   CDEMO-MENU-OPT-NUM(idx)     - option number
      *   CDEMO-MENU-OPT-NAME(idx)    - display label
      *   CDEMO-MENU-OPT-PGMNAME(idx) - CICS program name
      *   CDEMO-MENU-OPT-USRTYPE(idx) - required user type
      *
         05 CDEMO-MENU-OPTIONS REDEFINES CDEMO-MENU-OPTIONS-DATA.
           10 CDEMO-MENU-OPT OCCURS 12 TIMES.
             15 CDEMO-MENU-OPT-NUM           PIC 9(02).
             15 CDEMO-MENU-OPT-NAME          PIC X(35).
             15 CDEMO-MENU-OPT-PGMNAME       PIC X(08).
             15 CDEMO-MENU-OPT-USRTYPE       PIC X(01).
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
