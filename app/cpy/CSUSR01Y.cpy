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
      * Record Layout Copybook: User security record (80 bytes)
      * Defines the record structure for the USRSEC VSAM KSDS
      * dataset used for authentication and user administration.
      * Primary key: SEC-USR-ID (8 bytes, position 1)
      *
      * Consuming Programs:
      *   COSGN00C  - Authentication (login validation)
      *   COUSR00C  - User list browse (admin function)
      *   COUSR01C  - User add (admin function)
      *   COUSR02C  - User update (admin function)
      *   COUSR03C  - User delete (admin function)
      *   COACTUPC  - Account update (user lookup)
      *   COACTVWC  - Account view (user lookup)
      *   COCRDLIC  - Card list browse (user context)
      *   COCRDSLC  - Card detail view (user context)
      *   COCRDUPC  - Card update (user context)
      *   COADM01C  - Admin menu (user type routing)
      *   COMEN01C  - Main menu (user type routing)
      *
      * Data Source: app/jcl/DUSRSECJ.jcl (inline SYSIN data)
      * Cross-Ref:  COCOM01Y.cpy (CDEMO-USRTYP-ADMIN/USER)
      * Parallel:   UNUSED1Y.cpy (same structure, UNUSED- prefix)
      *
      * Total record size: 80 bytes (8+20+20+8+1+23)
      *
       01 SEC-USER-DATA.
      * Bytes 1-8: User ID - primary key for USRSEC
      *   VSAM dataset. E.g. 'ADMIN001', 'USER0001'
         05 SEC-USR-ID                 PIC X(08).
      * Bytes 9-28: User first name for display purposes
         05 SEC-USR-FNAME              PIC X(20).
      * Bytes 29-48: User last name for display purposes
         05 SEC-USR-LNAME              PIC X(20).
      * Bytes 49-56: Plain-text password
      *   Demo application - not production-grade security
         05 SEC-USR-PWD                PIC X(08).
      * Byte 57: User type indicator
      *   'A' = Admin (routes to COADM01C admin menu)
      *   'U' = Regular user (routes to COMEN01C main menu)
      *   Maps to 88-level conditions in COCOM01Y.cpy:
      *     CDEMO-USRTYP-ADMIN / CDEMO-USRTYP-USER
         05 SEC-USR-TYPE               PIC X(01).
      * Bytes 58-80: Reserved for future expansion
         05 SEC-USR-FILLER             PIC X(23).
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:59 CDT
      *
