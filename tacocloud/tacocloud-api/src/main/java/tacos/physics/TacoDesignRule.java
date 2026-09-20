package tacos.physics;

import java.util.List;

public interface TacoDesignRule {

// Valida el contexto del diseño del taco y retorna la lista de violaciones.
  List<DesignViolation> validate(TacoDesignContext context);
}
