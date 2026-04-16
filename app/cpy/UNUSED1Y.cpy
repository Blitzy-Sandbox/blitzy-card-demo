      *----------------------------------------------------------------*
      * Record Layout Copybook: Reserved/unused 80-byte layout
      *
      * This copybook defines a placeholder record with the same
      * field structure as CSUSR01Y.cpy (SEC-USER-DATA) but with
      * UNUSED- prefix. It is not actively COPY-included by any
      * CardDemo program.
      *
      * Retained in the repository as a structural placeholder.
      *
      * Total record length: 80 bytes (8+20+20+8+1+23)
      *
      * Cross-reference: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA)
      *----------------------------------------------------------------*
       01 UNUSED-DATA.
      * Parallels SEC-USR-ID in CSUSR01Y (bytes 1-8)
         05 UNUSED-ID                 PIC X(08).
      * Parallels SEC-USR-FNAME in CSUSR01Y (bytes 9-28)
         05 UNUSED-FNAME              PIC X(20).
      * Parallels SEC-USR-LNAME in CSUSR01Y (bytes 29-48)
         05 UNUSED-LNAME              PIC X(20).
      * Parallels SEC-USR-PWD in CSUSR01Y (bytes 49-56)
         05 UNUSED-PWD                PIC X(08).
      * Parallels SEC-USR-TYPE in CSUSR01Y (byte 57)
         05 UNUSED-TYPE               PIC X(01).
      * Parallels SEC-USR-FILLER in CSUSR01Y (bytes 58-80)
         05 UNUSED-FILLER             PIC X(23).
      *
      * Ver: CardDemo_v1.0-56-gd8e5ebf-109 Date: 2022-08-19 17:55:18 CDT
      *
