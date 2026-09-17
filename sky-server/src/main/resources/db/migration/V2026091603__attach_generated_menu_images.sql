-- Generated menu assets are packaged at classpath:/static and served by the
-- dedicated /dishes and /setmeals endpoints.
UPDATE dish
SET image = CONCAT('/dishes/dish-', id, '.png')
WHERE status = 1;

UPDATE setmeal
SET image = CONCAT('/setmeals/setmeal-', id, '.png')
WHERE status = 1;
