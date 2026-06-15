package com.carddemo.model.entity;

import com.carddemo.model.enums.UserType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * User security entity. JPA mapping of the COBOL SEC-USER-DATA record (copybook
 * CSUSR01Y, RECLN 80). Re-platforms the VSAM USRSEC KSDS onto the user_security
 * table. The password column stores a BCrypt hash and the user-type column
 * stores the single-character code via UserTypeConverter.
 */
@Entity
@Table(name = "user_security")
public class UserSecurity {

    @Id
    @Column(name = "sec_usr_id", nullable = false, length = 8)
    private String secUsrId;

    @Column(name = "sec_usr_fname", nullable = false, length = 20)
    private String secUsrFname;

    @Column(name = "sec_usr_lname", nullable = false, length = 20)
    private String secUsrLname;

    @Column(name = "sec_usr_pwd", nullable = false, length = 60)
    private String secUsrPwd;

    @Convert(converter = UserTypeConverter.class)
    @Column(name = "sec_usr_type", nullable = false, length = 1)
    private UserType secUsrType;

    public UserSecurity() {
    }

    public String getSecUsrId() { return secUsrId; }
    public void setSecUsrId(String secUsrId) { this.secUsrId = secUsrId; }
    public String getSecUsrFname() { return secUsrFname; }
    public void setSecUsrFname(String v) { this.secUsrFname = v; }
    public String getSecUsrLname() { return secUsrLname; }
    public void setSecUsrLname(String v) { this.secUsrLname = v; }
    public String getSecUsrPwd() { return secUsrPwd; }
    public void setSecUsrPwd(String v) { this.secUsrPwd = v; }
    public UserType getSecUsrType() { return secUsrType; }
    public void setSecUsrType(UserType v) { this.secUsrType = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        UserSecurity that = (UserSecurity) o;
        return Objects.equals(secUsrId, that.secUsrId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(secUsrId);
    }

    @Converter
    public static class UserTypeConverter implements AttributeConverter<UserType, String> {
        @Override
        public String convertToDatabaseColumn(UserType attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public UserType convertToEntityAttribute(String dbData) {
            return UserType.fromCode(dbData);
        }
    }
}
