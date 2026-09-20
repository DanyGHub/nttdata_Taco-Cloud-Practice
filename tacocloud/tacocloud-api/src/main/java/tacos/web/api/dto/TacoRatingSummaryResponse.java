package tacos.web.api.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TacoRatingSummaryResponse {

  private String tacoId;
  private double averageScore;
  private long totalVotes;
  private Map<Integer, Long> distribution;
  private Integer userScore;

}
