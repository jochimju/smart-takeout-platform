-- New menu maintenance requires a concrete restaurant. This backfill is intentionally conservative:
-- only infer a missing menu item's restaurant when its category is already scoped.

update dish d
join category c on c.id = d.category_id
set d.canteen_id = c.canteen_id
where d.canteen_id is null and c.canteen_id is not null;

update setmeal s
join category c on c.id = s.category_id
set s.canteen_id = c.canteen_id
where s.canteen_id is null and c.canteen_id is not null;

-- Do not assign an arbitrary restaurant to rows that cannot be inferred safely.
-- They remain unavailable to the restaurant-scoped C end until an operator assigns them.
