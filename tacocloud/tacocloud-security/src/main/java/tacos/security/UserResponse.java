package tacos.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


// DTO without password and hashes.

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

  private String id;
  private String username;
  private String fullname;
  private String street;
  private String city;
  private String state;
  private String zip;
  private String phoneNumber;
  private String email;

}
