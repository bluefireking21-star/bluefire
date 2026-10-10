package com.gofast;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Go Fast: a speed-platformer gameplay mod.
 *
 * Abilities (all original implementations, no character model or art):
 *  - Rings as health (lose rings when hit, they scatter)
 *  - Spin Dash: hold sneak on the ground while holding a Boost Orb, release to launch
 *  - Boost Dash: right-click a Boost Orb to dash where you look
 *  - Homing Attack: right-click the Boost Orb in mid-air near a hostile mob to lock on and strike it
 *  - Dash Pad block
 *  - Super form: hold 7 Chaos Emeralds and have 50+ rings, right-click to transform.
 *    You get flight, speed, jump, regeneration and can't be hurt, but it costs 1 ring per second.
 */
public class GoFastMod implements ModInitializer {
    public static final String MOD_ID = "gofast";

    private static final int MAX_RINGS_LOST = 20;
    private static final long HIT_COOLDOWN_TICKS = 40;

    private static final int SPIN_MIN_CHARGE = 15;
    private static final int SPIN_MAX_CHARGE = 50;
    private static final double HOMING_RANGE = 16.0;
    private static final int SUPER_MIN_RINGS = 50;
    private static final int EMERALDS_NEEDED = 7;

    public static Item RING;
    public static Item BOOST_ORB;
    public static Item CHAOS_EMERALD;
    public static Block DASH_PAD;

    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();
    private static final Map<UUID, Integer> SPIN_CHARGE = new HashMap<>();
    private static final Map<UUID, HomingState> HOMING = new HashMap<>();
    private static final Set<UUID> SUPER = new HashSet<>();

    private record HomingState(UUID target, long until) {}

    @Override
    public void onInitialize() {
        RING = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "ring"),
                new Item(new Item.Settings().maxCount(99)));

        BOOST_ORB = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "boost_orb"),
                new BoostOrbItem(new Item.Settings().maxCount(1)));

        CHAOS_EMERALD = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "chaos_emerald"),
                new ChaosEmeraldItem(new Item.Settings().maxCount(EMERALDS_NEEDED)));

        DASH_PAD = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "dash_pad"),
                new DashPadBlock(AbstractBlock.Settings.create()
                        .strength(0.5f)
                        .nonOpaque()
                        .sounds(BlockSoundGroup.METAL)
                        .pistonBehavior(PistonBehavior.DESTROY)));
        Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "dash_pad"),
                new BlockItem(DASH_PAD, new Item.Settings()));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(RING);
            entries.add(BOOST_ORB);
            entries.add(CHAOS_EMERALD);
            entries.add(DASH_PAD.asItem());
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register(GoFastMod::onDamage);
        ServerTickEvents.END_SERVER_TICK.register(GoFastMod::onServerTick);

        // Make sure nobody keeps flight if they log out while Super.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            if (SUPER.contains(player.getUuid())) {
                endSuper(player, false);
            }
            SPIN_CHARGE.remove(player.getUuid());
            HOMING.remove(player.getUuid());
            LAST_HIT.remove(player.getUuid());
        });
    }

    // ------------------------------------------------------------------
    // Rings as health
    // ------------------------------------------------------------------

    private static boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayerEntity player)) {
            return true;
        }
        if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return true; // void and /kill still work
        }
        if (SUPER.contains(player.getUuid())) {
            return false; // invincible while Super
        }
        int have = countRings(player);
        if (have <= 0) {
            return true;
        }

        ServerWorld world = player.getServerWorld();
        long now = world.getTime();
        Long last = LAST_HIT.get(player.getUuid());
        if (last != null && now - last < HIT_COOLDOWN_TICKS) {
            return false;
        }
        LAST_HIT.put(player.getUuid(), now);

        int lost = Math.min(have, MAX_RINGS_LOST);
        removeRings(player, lost);
        scatterRings(world, player, lost);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK,
                SoundCategory.PLAYERS, 1.0f, 1.4f);
        return false;
    }

    private static int countRings(PlayerEntity player) {
        int total = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(RING)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void removeRings(PlayerEntity player, int amount) {
        int left = amount;
        for (int i = 0; i < player.getInventory().size() && left > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(RING)) {
                int take = Math.min(left, stack.getCount());
                stack.decrement(take);
                left -= take;
            }
        }
    }

    private static void scatterRings(ServerWorld world, PlayerEntity player, int count) {
        Random random = player.getRandom();
        for (int i = 0; i < count; i++) {
            ItemEntity ring = new ItemEntity(world, player.getX(), player.getY() + 1.0, player.getZ(),
                    new ItemStack(RING));
            ring.setVelocity(
                    (random.nextDouble() - 0.5) * 0.6,
                    0.3 + random.nextDouble() * 0.3,
                    (random.nextDouble() - 0.5) * 0.6);
            ring.setPickupDelay(40);
            world.spawnEntity(ring);
        }
    }

    // ------------------------------------------------------------------
    // Per-tick abilities
    // ------------------------------------------------------------------

    private static void onServerTick(MinecraftServer server) {
        long ticks = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            tickSpinDash(player);
            tickHoming(player);
            tickSuper(player, ticks);
        }
    }

    private static boolean holdingBoostOrb(PlayerEntity player) {
        return player.getMainHandStack().isOf(BOOST_ORB) || player.getOffHandStack().isOf(BOOST_ORB);
    }

    /** Hold sneak on the ground with a Boost Orb in hand to charge, release to launch. */
    private static void tickSpinDash(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        int charge = SPIN_CHARGE.getOrDefault(id, 0);
        ServerWorld world = player.getServerWorld();

        if (player.isSneaking() && player.isOnGround() && !player.hasVehicle() && holdingBoostOrb(player)) {
            if (charge < SPIN_MAX_CHARGE) {
                charge++;
            }
            SPIN_CHARGE.put(id, charge);
            world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(),
                    2, 0.2, 0.05, 0.2, 0.01);
            if (charge % 5 == 0) {
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategory.PLAYERS, 0.6f, 0.8f + charge * 0.02f);
            }
        } else if (charge > 0) {
            SPIN_CHARGE.remove(id);
            if (charge >= SPIN_MIN_CHARGE && player.isOnGround()) {
                Vec3d look = player.getRotationVec(1.0F);
                Vec3d dir = new Vec3d(look.x, 0, look.z).normalize();
                double power = 1.0 + 2.0 * ((double) charge / SPIN_MAX_CHARGE);
                player.setVelocity(dir.x * power, 0.1, dir.z * power);
                player.velocityModified = true;
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH,
                        SoundCategory.PLAYERS, 1.0f, 1.0f);
                world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(),
                        20, 0.4, 0.1, 0.4, 0.08);
            }
        }
    }

    /** Keeps flying at the locked-on mob until it connects or the lock expires. */
    private static void tickHoming(ServerPlayerEntity player) {
        HomingState state = HOMING.get(player.getUuid());
        if (state == null) {
            return;
        }
        ServerWorld world = player.getServerWorld();
        Entity found = world.getEntity(state.target());
        if (!(found instanceof LivingEntity target) || !target.isAlive() || world.getTime() > state.until()) {
            HOMING.remove(player.getUuid());
            return;
        }

        Vec3d toTarget = target.getPos().add(0, target.getHeight() / 2, 0)
                .subtract(player.getPos().add(0, player.getHeight() / 2, 0));
        if (toTarget.length() < 1.6) {
            target.damage(world.getDamageSources().playerAttack(player), 8.0f);
            player.setVelocity(0, 0.7, 0); // bounce off like a real homing attack
            player.velocityModified = true;
            player.fallDistance = 0;
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
            world.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(),
                    20, 0.4, 0.4, 0.4, 0.2);
            HOMING.remove(player.getUuid());
            return;
        }

        player.setVelocity(toTarget.normalize().multiply(1.8));
        player.velocityModified = true;
        player.fallDistance = 0;
        world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.8, player.getZ(),
                3, 0.1, 0.1, 0.1, 0.01);
    }

    private static void tickSuper(ServerPlayerEntity player, long ticks) {
        UUID id = player.getUuid();
        if (!SUPER.contains(id)) {
            return;
        }
        ServerWorld world = player.getServerWorld();

        if (ticks % 20 == 0) {
            removeRings(player, 1);
            if (countRings(player) <= 0) {
                endSuper(player, true);
                return;
            }
        }
        if (ticks % 10 == 0) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30, 5, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 30, 3, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 30, 1, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, 30, 0, false, false));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false));
        }
        world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(),
                2, 0.3, 0.5, 0.3, 0.02);
    }

    private static void startSuper(ServerPlayerEntity player) {
        SUPER.add(player.getUuid());
        player.getAbilities().allowFlying = true;
        player.sendAbilitiesUpdate();
        player.sendMessage(Text.literal("Super form! Double-tap jump to fly."), true);
        player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE,
                SoundCategory.PLAYERS, 1.0f, 1.5f);
    }

    private static void endSuper(ServerPlayerEntity player, boolean notify) {
        SUPER.remove(player.getUuid());
        if (!player.isCreative() && !player.isSpectator()) {
            player.getAbilities().allowFlying = false;
            player.getAbilities().flying = false;
            player.sendAbilitiesUpdate();
        }
        player.removeStatusEffect(StatusEffects.SPEED);
        player.removeStatusEffect(StatusEffects.JUMP_BOOST);
        player.removeStatusEffect(StatusEffects.REGENERATION);
        player.removeStatusEffect(StatusEffects.SATURATION);
        player.removeStatusEffect(StatusEffects.GLOWING);
        if (notify) {
            player.sendMessage(Text.literal("Super form ended."), true);
            player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_DEACTIVATE,
                    SoundCategory.PLAYERS, 1.0f, 1.2f);
        }
    }

    // ------------------------------------------------------------------
    // Items and blocks
    // ------------------------------------------------------------------

    /**
     * Right-click: dash where you look. In mid-air with a hostile mob nearby
     * in front of you, it locks on and performs a homing attack instead.
     */
    public static class BoostOrbItem extends Item {
        public BoostOrbItem(Settings settings) {
            super(settings);
        }

        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (user.getItemCooldownManager().isCoolingDown(this)) {
                return TypedActionResult.fail(stack);
            }
            if (!world.isClient && user instanceof ServerPlayerEntity player) {
                ServerWorld serverWorld = (ServerWorld) world;
                LivingEntity target = player.isOnGround() ? null : findHomingTarget(player, serverWorld);
                if (target != null) {
                    HOMING.put(player.getUuid(), new HomingState(target.getUuid(), serverWorld.getTime() + 30));
                    world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP,
                            SoundCategory.PLAYERS, 1.0f, 1.6f);
                } else {
                    Vec3d look = player.getRotationVec(1.0F);
                    player.setVelocity(look.multiply(2.5));
                    player.velocityModified = true;
                    player.fallDistance = 0;
                    world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH,
                            SoundCategory.PLAYERS, 1.0f, 1.3f);
                    serverWorld.spawnParticles(ParticleTypes.CLOUD,
                            player.getX(), player.getY() + 0.5, player.getZ(), 15, 0.3, 0.3, 0.3, 0.05);
                }
            }
            user.getItemCooldownManager().set(this, 30);
            return TypedActionResult.success(stack, world.isClient());
        }

        private static LivingEntity findHomingTarget(ServerPlayerEntity player, ServerWorld world) {
            Vec3d look = player.getRotationVec(1.0F);
            List<HostileEntity> candidates = world.getEntitiesByClass(HostileEntity.class,
                    player.getBoundingBox().expand(HOMING_RANGE), e -> e.isAlive());
            HostileEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (HostileEntity mob : candidates) {
                Vec3d to = mob.getPos().subtract(player.getPos());
                if (to.length() < 0.1 || to.normalize().dotProduct(look) < 0.3) {
                    continue; // must be roughly in front of you
                }
                double dist = to.length();
                if (dist < bestDist) {
                    bestDist = dist;
                    best = mob;
                }
            }
            return best;
        }
    }

    /** Hold 7 of these and 50+ rings, then right-click to go Super. Right-click again to cancel. */
    public static class ChaosEmeraldItem extends Item {
        public ChaosEmeraldItem(Settings settings) {
            super(settings);
        }

        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient && user instanceof ServerPlayerEntity player) {
                if (SUPER.contains(player.getUuid())) {
                    endSuper(player, true);
                } else if (stack.getCount() < EMERALDS_NEEDED) {
                    player.sendMessage(Text.literal("Hold " + EMERALDS_NEEDED + " Chaos Emeralds in one stack."), true);
                } else if (countRings(player) < SUPER_MIN_RINGS) {
                    player.sendMessage(Text.literal("You need at least " + SUPER_MIN_RINGS + " rings."), true);
                } else {
                    startSuper(player);
                }
            }
            return TypedActionResult.success(stack, world.isClient());
        }
    }

    /** Step on it to be launched in the direction you are facing. */
    public static class DashPadBlock extends Block {
        private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 1, 16);

        public DashPadBlock(Settings settings) {
            super(settings);
        }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return SHAPE;
        }

        @Override
        public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
            if (!world.isClient && entity instanceof LivingEntity) {
                Vec3d look = entity.getRotationVec(1.0F);
                Vec3d dir = new Vec3d(look.x, 0, look.z).normalize();
                entity.setVelocity(dir.x * 2.5, 0.2, dir.z * 2.5);
                entity.velocityModified = true;
                world.playSound(null, pos, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH,
                        SoundCategory.BLOCKS, 1.0f, 1.5f);
            }
            super.onSteppedOn(world, pos, state, entity);
        }
    }
}
