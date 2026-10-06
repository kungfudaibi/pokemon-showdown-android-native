package dev.local.showdownnative;

public final class TeamStoreTest {
    public static void main(String[] args) {
        String source = "Articuno @ Leftovers\n" +
                "Ability: Pressure\n" +
                "EVs: 252 HP / 252 SpA / 4 SpD\n" +
                "Modest Nature\n" +
                "IVs: 30 SpA / 30 SpD\n" +
                "- Ice Beam\n" +
                "- Hurricane\n" +
                "- Substitute\n" +
                "- Roost";
        String actual = TeamStore.pack(source);
        String expected = "Articuno||leftovers|pressure|icebeam,hurricane,substitute,roost|Modest|252,,,252,4,||,,,30,30,|||";
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
        if (!TeamStore.pack(source + "\n\n" + source).equals(expected + "]" + expected))
            throw new AssertionError("Multiple sets were not separated correctly");
        try { TeamStore.pack("Pikachu\nAbility: Static"); throw new AssertionError("Missing moves accepted"); }
        catch (IllegalArgumentException expectedError) { }
        System.out.println("Team packing tests passed");
    }
}
