package org.ajikhoji.ajiorders.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountInfoUpdateDto {

    private Integer userId;
    private String jwtLoginToken;
    private String updatedAddress;
    private String updatedPassword;

}
