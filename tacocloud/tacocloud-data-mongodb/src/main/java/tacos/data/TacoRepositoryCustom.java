package tacos.data;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;

public interface TacoRepositoryCustom {

  Mono<TacoPage<Taco>> searchTacos(TacoSearchCriteria criteria);
}
