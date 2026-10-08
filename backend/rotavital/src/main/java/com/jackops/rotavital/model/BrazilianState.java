package com.jackops.rotavital.model;

/**
 * Unidades federativas, com peso aproximado de população usado na geração dos dados.
 */
public enum BrazilianState {
    AC(1), AL(3), AP(1), AM(4), BA(14), CE(9), DF(3), ES(4), GO(7),
    MA(7), MT(4), MS(3), MG(21), PA(8), PB(4), PR(11), PE(9), PI(3),
    RJ(16), RN(3), RS(11), RO(2), RR(1), SC(8), SP(44), SE(2), TO(2);

    private final int populationWeight;

    BrazilianState(int populationWeight) {
        this.populationWeight = populationWeight;
    }

    public int getPopulationWeight() {
        return populationWeight;
    }
}
